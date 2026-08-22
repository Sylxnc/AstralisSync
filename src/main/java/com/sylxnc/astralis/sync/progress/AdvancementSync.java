package com.sylxnc.astralis.sync.progress;

import com.sylxnc.astralis.sync.Main;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Cross-server advancement progress sync.
 *
 * <p>Stores the awarded criteria of every advancement as GZIP-compressed
 * UTF-8 lines of {@code namespace:key|criterion1;criterion2} inside
 * {@code advancement_data(uuid CHAR(36) PK, payload LONGBLOB, updated_at)}.</p>
 *
 * <p>The table is created lazily on first use; all DB I/O runs async on the
 * Bukkit scheduler. Methods never throw.</p>
 */
public final class AdvancementSync {

    private final Main plugin;

    /** Set once the CREATE TABLE has been issued successfully. */
    private volatile boolean tableReady = false;
    private final Object initLock = new Object();

    public AdvancementSync(Main plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("advancements.enabled", true);
    }

    /**
     * Collects all awarded criteria for this player from the server's
     * advancement registry and writes them to MySQL asynchronously.
     * Must be called on the main thread (reads live player state).
     */
    public void captureAndStore(Player player) {
        if (!enabled() || player == null || !player.isOnline()) {
            return;
        }
        ensureTableAsync();
        Map<String, String> awardedByAdvancement = new LinkedHashMap<>();
        try {
            var iterator = Bukkit.advancementIterator();
            while (iterator.hasNext()) {
                Advancement adv = iterator.next();
                try {
                    AdvancementProgress prog = player.getAdvancementProgress(adv);
                    if (prog == null) {
                        continue;
                    }
                    var criteria = prog.getAwardedCriteria();
                    if (criteria == null || criteria.isEmpty()) {
                        continue;
                    }
                    awardedByAdvancement.put(adv.getKey().toString(), String.join(";", criteria));
                } catch (Exception perAdv) {
                    // Unsupported/removed advancement for this player - skip it.
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Advancement capture failed for " + player.getName(), e);
            return;
        }
        if (awardedByAdvancement.isEmpty()) {
            return;
        }
        byte[] payload = compress(serialize(awardedByAdvancement));
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> store(uuid, payload));
    }

    /**
     * Re-awards criteria stored in MySQL that the local player is still
     * missing. Never revokes anything. Safe to call from any thread: the
     * read happens async, awarding is scheduled back to the main thread.
     */
    public void applyTo(Player player) {
        if (!enabled() || player == null || !player.isOnline()) {
            return;
        }
        ensureTableAsync();
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            byte[] payload = load(uuid);
            if (payload == null || payload.length == 0) {
                return;
            }
            Map<String, String> stored;
            try {
                stored = deserialize(decompress(payload));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Corrupt advancement payload for " + uuid, e);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> awardMissing(player, stored));
        });
    }

    /** Main-thread: awards every stored criterion that is not yet earned. */
    private void awardMissing(Player player, Map<String, String> stored) {
        if (!player.isOnline()) {
            return;
        }
        int awarded = 0;
        for (Map.Entry<String, String> entry : stored.entrySet()) {
            Advancement adv;
            try {
                NamespacedKey key = NamespacedKey.fromString(entry.getKey());
                adv = key == null ? null : Bukkit.getAdvancement(key);
            } catch (Exception e) {
                adv = null;
            }
            if (adv == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            try {
                AdvancementProgress prog = player.getAdvancementProgress(adv);
                if (prog == null) {
                    continue;
                }
                Set<String> missing = new LinkedHashSet<String>(Arrays.asList(entry.getValue().split(";")));
                missing.removeAll(prog.getAwardedCriteria());
                for (String criterion : missing) {
                    try {
                        if (prog.awardCriteria(criterion)) {
                            awarded++;
                        }
                    } catch (Exception perCriterion) {
                        // Criterion no longer exists - skip it.
                    }
                }
            } catch (Exception perAdv) {
                // Advancement became unsupported - skip it.
            }
        }
        if (awarded > 0) {
            final int count = awarded;
            plugin.getLogger().fine(() -> "Restored " + count + " advancement criteria for " + player.getName());
        }
    }

    /* ------------------------------------------------------------------
     * Persistence
     * ------------------------------------------------------------------ */

    private void ensureTableAsync() {
        if (tableReady) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::ensureTableBlocking);
    }

    private void ensureTableBlocking() {
        synchronized (initLock) {
            if (tableReady) {
                return;
            }
            String sql = """
                    CREATE TABLE IF NOT EXISTS advancement_data (
                        uuid       CHAR(36)  NOT NULL PRIMARY KEY,
                        payload    LONGBLOB  NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """;
            try (Connection conn = source().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.executeUpdate();
                tableReady = true;
                plugin.getLogger().fine("Advancement table ready.");
            } catch (SQLException | IllegalStateException e) {
                logSql("table init", e);
            }
        }
    }

    private javax.sql.DataSource source() {
        var src = plugin.getDatabaseManager().getSource();
        if (src == null) {
            throw new IllegalStateException("Database pool not available");
        }
        return src;
    }

    private void store(UUID uuid, byte[] payload) {
        if (!tableReady && !tryInitTable()) {
            return;
        }
        String sql = """
                INSERT INTO advancement_data (uuid, payload) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE payload = VALUES(payload)
                """;
        try (Connection conn = source().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setBytes(2, payload);
            ps.executeUpdate();
        } catch (SQLException | IllegalStateException e) {
            logSql("save", e);
        }
    }

    private byte[] load(UUID uuid) {
        if (!tableReady && !tryInitTable()) {
            return null;
        }
        String sql = "SELECT payload FROM advancement_data WHERE uuid = ?";
        try (Connection conn = source().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes("payload") : null;
            }
        } catch (SQLException | IllegalStateException e) {
            logSql("load", e);
            return null;
        }
    }

    /** Returns true once the table exists (or already did). */
    private boolean tryInitTable() {
        ensureTableBlocking();
        return tableReady;
    }

    private void logSql(String action, Exception e) {
        plugin.getLogger().log(Level.SEVERE, "Advancement sync " + action + " failed: " + e.getMessage(), e);
    }

    /* ------------------------------------------------------------------
     * Serialization: "namespace:key|crit1;crit2" lines, GZIP-compressed
     * ------------------------------------------------------------------ */

    private static String serialize(Map<String, String> data) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : data.entrySet()) {
            sb.append(entry.getKey()).append('|').append(entry.getValue()).append('\n');
        }
        return sb.toString();
    }

    private static Map<String, String> deserialize(String text) {
        Map<String, String> data = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            line = line.trim();
            int sep = line.indexOf('|');
            if (sep <= 0) {
                continue;
            }
            data.put(line.substring(0, sep), line.substring(sep + 1));
        }
        return data;
    }

    private static byte[] compress(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("GZIP compression failed", e);
        }
        return out.toByteArray();
    }

    private static String decompress(byte[] bytes) throws Exception {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
