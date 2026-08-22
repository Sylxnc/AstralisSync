package com.sylxnc.astralis.sync.progress;

import com.sylxnc.astralis.sync.Main;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Cross-server statistics sync (untyped {@link Statistic.Type#UNTYPED}
 * statistics only).
 *
 * <p>Stores one GZIP-compressed UTF-8 line per statistic,
 * {@code STATISTIC_NAME=value}, inside
 * {@code statistics_data(uuid CHAR(36) PK, payload LONGBLOB, updated_at)}.</p>
 *
 * <p>Known limitation: ITEM-, BLOCK- and ENTITY-typed statistics
 * ({@code MINE_BLOCK}, {@code KILL_ENTITY}, ...) are not synced; only the
 * untyped variants supported by {@code player.getStatistic(Statistic)} are.</p>
 *
 * <p>The table is created lazily on first use; all DB I/O runs async on the
 * Bukkit scheduler. Methods never throw.</p>
 */
public final class StatisticsSync {

    private final Main plugin;

    /** Set once the CREATE TABLE has been issued successfully. */
    private volatile boolean tableReady = false;
    private final Object initLock = new Object();

    public StatisticsSync(Main plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("statistics.enabled", true);
    }

    /**
     * Reads every untyped statistic from the player and writes them to MySQL
     * asynchronously. Must be called on the main thread.
     */
    public void captureAndStore(Player player) {
        if (!enabled() || player == null || !player.isOnline()) {
            return;
        }
        ensureTableAsync();
        Map<String, Integer> values = new LinkedHashMap<>();
        for (Statistic stat : Statistic.values()) {
            if (stat.getType() != Statistic.Type.UNTYPED) {
                continue;
            }
            try {
                values.put(stat.name(), player.getStatistic(stat));
            } catch (Exception unsupported) {
                // Not readable as untyped on this server - skip it.
            }
        }
        if (values.isEmpty()) {
            return;
        }
        byte[] payload = compress(serialize(values));
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> store(uuid, payload));
    }

    /**
     * Restores stored statistic values onto the local player via
     * {@code setStatistic}, clamped at >= 0 and skipping failures. Read-only
     * stats that reject writes are skipped silently by design. Safe to call
     * from any thread: the read happens async, applying is scheduled back to
     * the main thread.
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
            Map<String, Integer> stored;
            try {
                stored = deserialize(decompress(payload));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Corrupt statistics payload for " + uuid, e);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> applyValues(player, stored));
        });
    }

    /** Main-thread: sets each stored value, never below zero, skip failures. */
    private void applyValues(Player player, Map<String, Integer> stored) {
        if (!player.isOnline()) {
            return;
        }
        int applied = 0;
        for (Map.Entry<String, Integer> entry : stored.entrySet()) {
            Statistic stat;
            try {
                stat = Statistic.valueOf(entry.getKey());
            } catch (IllegalArgumentException unknown) {
                continue; // Removed/renamed in this server version - skip it.
            }
            if (stat.getType() != Statistic.Type.UNTYPED || entry.getValue() == null) {
                continue;
            }
            int value = Math.max(0, entry.getValue());
            try {
                // Never lower a higher local value: take the max of both.
                int current = player.getStatistic(stat);
                if (value > current) {
                    player.setStatistic(stat, value);
                    applied++;
                }
            } catch (Exception unsupported) {
                // Read-only or otherwise unwritable statistic - skip it.
            }
        }
        if (applied > 0) {
            final int count = applied;
            plugin.getLogger().fine(() -> "Restored " + count + " statistics for " + player.getName());
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
                    CREATE TABLE IF NOT EXISTS statistics_data (
                        uuid       CHAR(36)  NOT NULL PRIMARY KEY,
                        payload    LONGBLOB  NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """;
            try (Connection conn = source().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.executeUpdate();
                tableReady = true;
                plugin.getLogger().fine("Statistics table ready.");
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
                INSERT INTO statistics_data (uuid, payload) VALUES (?, ?)
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
        String sql = "SELECT payload FROM statistics_data WHERE uuid = ?";
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
        plugin.getLogger().log(Level.SEVERE, "Statistics sync " + action + " failed: " + e.getMessage(), e);
    }

    /* ------------------------------------------------------------------
     * Serialization: "STATISTIC_NAME=value" lines, GZIP-compressed
     * ------------------------------------------------------------------ */

    private static String serialize(Map<String, Integer> values) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        return sb.toString();
    }

    private static Map<String, Integer> deserialize(String text) {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            line = line.trim();
            int sep = line.indexOf('=');
            if (sep <= 0) {
                continue;
            }
            try {
                values.put(line.substring(0, sep), Integer.parseInt(line.substring(sep + 1)));
            } catch (NumberFormatException badValue) {
                // Skip malformed entry.
            }
        }
        return values;
    }

    private static byte[] compress(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
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
