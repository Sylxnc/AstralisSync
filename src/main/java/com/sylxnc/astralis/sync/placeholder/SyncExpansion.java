package com.sylxnc.astralis.sync.placeholder;

import com.sylxnc.astralis.sync.Main;
import com.sylxnc.astralis.sync.db.DatabaseManager;
import com.sylxnc.astralis.sync.enderchest.EnderChestManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * PlaceholderAPI support for AstralisSync ("%astralissync_*%").
 *
 * <p>The public entry point is {@link #register(Main)}. It is safe to call
 * whether or not PlaceholderAPI is installed: the actual expansion class is
 * only loaded once the presence guard passed, so no LinkageError can occur
 * on servers without PlaceholderAPI.</p>
 *
 * <p>Supported placeholders:</p>
 * <ul>
 *   <li>%astralissync_ecrows% - ender chest rows of the viewing player</li>
 *   <li>%astralissync_ecrows_&lt;name&gt;% - rows of an online or offline player</li>
 *   <li>%astralissync_ecrows_max% - configured maximum rows</li>
 *   <li>%astralissync_server% - configured server-id</li>
 *   <li>%astralissync_online% - local online count (network-wide number is
 *       unknown; Redis carries no global player registry)</li>
 *   <li>%astralissync_snapshot_count% / _&lt;name&gt;% - stored snapshots,
 *       served from an async-refreshed cache so the main thread never blocks</li>
 * </ul>
 *
 * Every placeholder catches all failures and falls back to "" (unknown
 * placeholders yield null so PlaceholderAPI leaves them untouched).
 */
public final class SyncExpansion {

    /** Name -> uuid cache TTL for offline lookups. */
    private static final long UUID_TTL_MS = 300_000L;
    /** How long a cached snapshot count is served before an async refresh. */
    private static final long COUNT_TTL_MS = 30_000L;
    /** Safety valve in case a count fetch never completes. */
    private static final long FETCH_TIMEOUT_MS = 15_000L;

    private SyncExpansion() {
    }

    /**
     * Registers the expansion with PlaceholderAPI. Logs an info message in
     * either case. Call once from Main#onEnable after the managers exist:
     * <pre>com.sylxnc.astralis.sync.placeholder.SyncExpansion.register(this);</pre>
     */
    public static void register(Main plugin) {
        try {
            if (plugin == null || Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
                if (plugin != null) {
                    plugin.getLogger().info("PlaceholderAPI not found - %astralissync_*% placeholders stay unavailable.");
                }
                return;
            }
            boolean ok = new Expansion(plugin).register();
            plugin.getLogger().info(ok
                    ? "PlaceholderAPI expansion registered: %astralissync_<placeholder>%"
                    : "PlaceholderAPI expansion 'astralissync' could not be registered (duplicate identifier?).");
        } catch (RuntimeException e) {
            if (plugin != null) {
                plugin.getLogger().warning("Could not register PlaceholderAPI expansion: " + e.getMessage());
            }
        }
    }

    /** Nested so that loading this class (and thus PlaceholderAPI types) is deferred until PAPI is known to exist. */
    private static final class Expansion extends PlaceholderExpansion {

        private record CachedUuid(UUID uuid, long at) {}

        private final Main plugin;
        private final Map<String, CachedUuid> uuidCache = new ConcurrentHashMap<>();
        private final Map<UUID, Integer> countCache = new ConcurrentHashMap<>();
        private final Map<UUID, Long> countCacheAt = new ConcurrentHashMap<>();
        private final Map<UUID, Long> countFetchInFlight = new ConcurrentHashMap<>();

        Expansion(Main plugin) {
            this.plugin = plugin;
        }

        @Override
        public String getIdentifier() {
            return "astralissync";
        }

        @Override
        public String getAuthor() {
            return "Sylxnc";
        }

        @Override
        public String getVersion() {
            return plugin.getPluginMeta().getVersion();
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public boolean canRegister() {
            return true;
        }

        @Override
        public String getRequiredPlugin() {
            return "AstralisSync";
        }

        @Override
        public String onRequest(OfflinePlayer player, String params) {
            try {
                String key = params == null ? "" : params.toLowerCase(Locale.ROOT);

                if (key.equals("server")) {
                    return serverId();
                }
                if (key.equals("online")) {
                    // Network-wide online count is unknown (Redis holds no global
                    // player registry), so this reports the LOCAL server's count.
                    return Integer.toString(Bukkit.getOnlinePlayers().size());
                }
                if (key.equals("ecrows_max")) {
                    EnderChestManager ec = plugin.getEnderChestManager();
                    return ec == null ? "" : Integer.toString(ec.maxRows());
                }
                if (key.equals("ecrows")) {
                    return ecRows(player, null);
                }
                if (key.startsWith("ecrows_")) {
                    return ecRows(player, params.substring("ecrows_".length()));
                }
                if (key.equals("snapshot_count")) {
                    return snapshotCount(player, null);
                }
                if (key.startsWith("snapshot_count_")) {
                    return snapshotCount(player, params.substring("snapshot_count_".length()));
                }
                return null; // unknown placeholder: leave untouched
            } catch (Exception e) {
                return "";
            }
        }

        private String serverId() {
            String id = plugin.getConfig().getString("server-id", "unknown");
            return id != null ? id : "unknown";
        }

        private String ecRows(OfflinePlayer target, String nameArg) {
            EnderChestManager ec = plugin.getEnderChestManager();
            if (ec == null) {
                return "";
            }
            UUID uuid = resolveUuid(target, nameArg);
            if (uuid == null) {
                return "";
            }
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                return Integer.toString(ec.getRows(uuid));
            }
            // Offline: read player_meta directly (own statement) so the
            // manager's row cache is not polluted with offline entries.
            int stored = storedEnderChestRows(uuid);
            int rows = stored > 0 ? Math.min(stored, ec.maxRows()) : ec.defaultRows();
            return Integer.toString(Math.max(1, rows));
        }

        private int storedEnderChestRows(UUID uuid) {
            try {
                DatabaseManager db = plugin.getDatabaseManager();
                if (db == null || !db.isConnected() || db.getSource() == null) {
                    return 0;
                }
                try (Connection conn = db.getSource().getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                             "SELECT enderchest_rows FROM player_meta WHERE uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getInt(1) : 0;
                    }
                }
            } catch (Exception e) {
                return 0;
            }
        }

        private String snapshotCount(OfflinePlayer target, String nameArg) {
            UUID uuid = resolveUuid(target, nameArg);
            if (uuid == null || plugin.getSnapshotManager() == null) {
                return "";
            }
            long now = System.currentTimeMillis();
            Long fetchedAt = countCacheAt.get(uuid);
            Integer cached = countCache.get(uuid);

            if (fetchedAt != null && cached != null && now - fetchedAt < COUNT_TTL_MS) {
                return Integer.toString(cached);
            }

            // Refresh asynchronously; SnapshotManager#countAsync hops to an
            // async thread for the query and back to the main thread for the
            // callback, so the requesting thread never blocks on JDBC.
            Long inFlight = countFetchInFlight.get(uuid);
            if (inFlight == null || now - inFlight > FETCH_TIMEOUT_MS) {
                countFetchInFlight.put(uuid, now);
                try {
                    plugin.getSnapshotManager().countAsync(uuid, (Consumer<Integer>) value -> {
                        countCache.put(uuid, value);
                        countCacheAt.put(uuid, System.currentTimeMillis());
                        countFetchInFlight.remove(uuid);
                    });
                } catch (RuntimeException e) {
                    countFetchInFlight.remove(uuid);
                }
            }
            // First-ever request has nothing cached yet; a later call serves it.
            return cached != null ? Integer.toString(cached) : "";
        }

        private UUID resolveUuid(OfflinePlayer target, String arg) {
            if (arg == null || arg.isBlank()) {
                return target != null ? target.getUniqueId() : null;
            }
            Player online = Bukkit.getPlayerExact(arg);
            if (online != null) {
                return online.getUniqueId();
            }
            String key = arg.toLowerCase(Locale.ROOT);
            CachedUuid cached = uuidCache.get(key);
            long now = System.currentTimeMillis();
            if (cached != null && now - cached.at() < UUID_TTL_MS) {
                return cached.uuid();
            }
            DatabaseManager db = plugin.getDatabaseManager();
            UUID resolved = db != null && db.isConnected() ? quietLookup(db, arg) : null;
            if (resolved != null) {
                if (uuidCache.size() > 512) {
                    uuidCache.clear();
                }
                uuidCache.put(key, new CachedUuid(resolved, now));
            }
            return resolved;
        }

        private UUID quietLookup(DatabaseManager db, String name) {
            try {
                return db.lookupUuid(name);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
