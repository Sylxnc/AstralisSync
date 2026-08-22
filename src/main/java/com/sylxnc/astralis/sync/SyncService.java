package com.sylxnc.astralis.sync;

import com.sylxnc.astralis.sync.db.DatabaseManager;
import com.sylxnc.astralis.sync.redis.RedisManager;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Central sync orchestration: load on join, snapshot + save + broadcast on quit.
 * Blocking I/O happens async; Bukkit API calls are applied back on the main thread.
 */
public final class SyncService {

    private final Main plugin;
    private final DatabaseManager database;
    private final RedisManager redis;

    public SyncService(Main plugin, DatabaseManager database, RedisManager redis) {
        this.plugin = plugin;
        this.database = database;
        this.redis = redis;
    }

    /* ------------------------------------------------------------------
     * Login handling
     * ------------------------------------------------------------------ */

    /**
     * Tries to acquire the distributed lock for the player before they are
     * transferred to this server. Returns true when this server owns the data.
     */
    public boolean acquireLock(UUID uuid) {
        return redis.tryLock(uuid);
    }

    /**
     * Async load of player data (Redis cache first, then MySQL). The result is
     * applied to the player on the main thread.
     */
    public CompletableFuture<Void> loadAndApply(Player player) {
        UUID uuid = player.getUniqueId();
        return CompletableFuture.supplyAsync(() -> {
            byte[] cached = redis.getCachedData(uuid);
            if (cached != null) {
                plugin.getLogger().fine(() -> "Cache hit for " + player.getName());
                return cached;
            }
            byte[] stored = database.loadData(uuid);
            if (stored != null) {
                redis.cacheData(uuid, stored);
            }
            return stored;
        }, r -> Bukkit.getScheduler().runTaskAsynchronously(plugin, r)).thenAccept(data -> {
            Consumer<Player> applier = (data == null) ? null : SnapshotCodec.readApplier(data);
            runSync(() -> {
                if (!player.isOnline()) {
                    return;
                }
                if (applier == null) {
                    // First join ever: initialize storage with a fresh snapshot.
                    savePlayer(player);
                } else {
                    applier.accept(player);
                    player.updateInventory();
                }
                // restore synced progress data after core data is in place
                if (plugin.getConfig().getBoolean("advancements.enabled", true)) {
                    plugin.getAdvancementSync().applyTo(player);
                }
                if (plugin.getConfig().getBoolean("statistics.enabled", true)) {
                    plugin.getStatisticsSync().applyTo(player);
                }
            });
        });
    }

    /* ------------------------------------------------------------------
     * Save handling
     * ------------------------------------------------------------------ */

    /** Snapshots the player and persists synchronously (used on shutdown). */
    public void savePlayerBlocking(Player player) {
        int ecRows = plugin.getEnderChestManager().getRows(player.getUniqueId());
        byte[] payload = SnapshotCodec.write(player, ecRows);
        database.saveData(uuid(player), player.getName(), payload, plugin.getConfig().getString("server-id"));
        redis.cacheData(uuid(player), payload);
        redis.broadcastUpdate(uuid(player));
    }

    /** Snapshots the player synchronously and persists async. */
    public void savePlayer(Player player) {
        int ecRows = plugin.getEnderChestManager().getRows(player.getUniqueId());
        byte[] payload = SnapshotCodec.write(player, ecRows);
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        String serverId = plugin.getConfig().getString("server-id", "unknown");

        CompletableFuture
                .supplyAsync(() -> database.saveData(uuid, name, payload, serverId),
                        r -> Bukkit.getScheduler().runTaskAsynchronously(plugin, r))
                .thenRun(() -> {
                    redis.cacheData(uuid, payload);
                    redis.broadcastUpdate(uuid);
                })
                .exceptionally(ex -> {
                    plugin.getLogger().severe("Failed saving " + name + ": " + ex.getMessage());
                    return null;
                });
    }

    /**
     * Loads data of a possibly offline/remote player for /invsee: Redis cache
     * first, then MySQL. Asks the remote server to save when the player is
     * online elsewhere and nothing is cached yet.
     */
    public void loadRemotePayload(org.bukkit.command.CommandSender requester,
                                  String targetName,
                                  java.util.function.BiConsumer<UUID, byte[]> callback) {
        CompletableFuture.supplyAsync(() -> {
            UUID uuid = database.lookupUuid(targetName);
            if (uuid == null) {
                return new Object[]{null, null};
            }
            byte[] cached = redis.getCachedData(uuid);
            if (cached != null) {
                return new Object[]{uuid, cached};
            }
            // ask whichever server owns the player to save now
            redis.requestSave(uuid);
            try {
                Thread.sleep(300); // small grace period for the round trip
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cached = redis.getCachedData(uuid);
            if (cached != null) {
                return new Object[]{uuid, cached};
            }
            return new Object[]{uuid, database.loadData(uuid)};
        }, r -> Bukkit.getScheduler().runTaskAsynchronously(plugin, r)).thenAccept(result -> {
            UUID uuid = (UUID) result[0];
            byte[] payload = (byte[]) result[1];
            runSync(() -> callback.accept(uuid, payload));
        });
    }

    public CompletableFuture<Void> saveAllOnline() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            futures.add(CompletableFuture.runAsync(() -> savePlayer(p)));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /** Called on quit: snapshot, persist, invalidate remote caches, release lock. */
    public void handleQuit(Player player) {
        plugin.getSnapshotManager().capture(player, "quit");
        if (plugin.getConfig().getBoolean("advancements.enabled", true)) {
            plugin.getAdvancementSync().captureAndStore(player);
        }
        if (plugin.getConfig().getBoolean("statistics.enabled", true)) {
            plugin.getStatisticsSync().captureAndStore(player);
        }
        savePlayer(player);
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            redis.unlock(uuid);
            plugin.getEnderChestManager().forget(uuid);
        }, 20L);
    }

    public boolean renewLock(UUID uuid) {
        return redis.renewLock(uuid);
    }

    private void runSync(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    private static UUID uuid(Player player) {
        return player.getUniqueId();
    }
}
