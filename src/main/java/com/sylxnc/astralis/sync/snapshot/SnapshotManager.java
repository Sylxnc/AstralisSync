package com.sylxnc.astralis.sync.snapshot;

import com.sylxnc.astralis.sync.Main;
import com.sylxnc.astralis.sync.SnapshotCodec;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Snapshot history: captures player states (pre-death, manual, quit) into
 * MySQL with a configurable rolling limit, browsable via GUI.
 */
public final class SnapshotManager {

    private final Main plugin;
    private final SnapshotStore store;

    /** Players for whom a snapshot is currently being written (dedupe). */
    private final Map<UUID, Boolean> pending = new ConcurrentHashMap<>();

    public SnapshotManager(Main plugin) {
        this.plugin = plugin;
        this.store = new SnapshotStore(plugin, plugin.getDatabaseManager().getSource());
    }

    public void init() {
        try {
            store.init();
        } catch (Exception e) {
            plugin.getLogger().severe("Could not init snapshot store: " + e.getMessage());
        }
    }

    public int maxSnapshots() {
        return Math.max(1, Math.min(200, plugin.getConfig().getInt("snapshots.max-per-player", 10)));
    }

    /** Async capture of the current state. */
    public void capture(Player player, String cause) {
        UUID uuid = player.getUniqueId();
        if (pending.putIfAbsent(uuid, true) != null) {
            return;
        }
        byte[] payload = SnapshotCodec.write(player);
        String name = player.getName();
        String serverId = plugin.getConfig().getString("server-id", "unknown");

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    store.insert(uuid, name, cause, serverId, payload);
                    store.prune(uuid, maxSnapshots());
                    new com.sylxnc.astralis.sync.api.event.SnapshotCapturedEvent(uuid, name, cause)
                            .callEvent();
                } finally {
                    pending.remove(uuid);
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    public void listAsync(UUID uuid, int limit, int offset, Consumer<List<SnapshotStore.Entry>> callback) {
        new BukkitRunnable() {
            @Override
            public void run() {
                List<SnapshotStore.Entry> entries = store.list(uuid, limit, offset);
                Bukkit.getScheduler().runTask(plugin, () -> callback.accept(entries));
            }
        }.runTaskAsynchronously(plugin);
    }

    public void countAsync(UUID uuid, Consumer<Integer> callback) {
        new BukkitRunnable() {
            @Override
            public void run() {
                int c = store.count(uuid);
                Bukkit.getScheduler().runTask(plugin, () -> callback.accept(c));
            }
        }.runTaskAsynchronously(plugin);
    }

    /** Loads a payload async and hands it to the callback on the main thread. */
    public void loadAsync(UUID uuid, long id, Consumer<ItemStack[]> inventoryPreview, Consumer<SnapshotCodec.Decoded> fullCallback) {
        new BukkitRunnable() {
            @Override
            public void run() {
                SnapshotStore.Entry entry = store.loadPayload(uuid, id);
                SnapshotCodec.Decoded decoded = entry == null ? null : SnapshotCodec.decode(entry.payload());
                Bukkit.getScheduler().runTask(plugin, () -> fullCallback.accept(decoded));
            }
        }.runTaskAsynchronously(plugin);
    }

    /** Restores the given snapshot onto the player (main thread). */
    public boolean restore(Player player, long id) {
        SnapshotStore.Entry entry = store.loadPayload(player.getUniqueId(), id);
        if (entry == null) {
            return false;
        }
        var event = new com.sylxnc.astralis.sync.api.event.SnapshotRestoreEvent(player, id);
        event.callEvent();
        if (event.isCancelled()) {
            return false;
        }
        // safety: snapshot the current state before overwriting it
        capture(player, "pre-restore");
        SnapshotCodec.Decoded decoded = SnapshotCodec.decode(entry.payload());
        if (decoded == null) {
            return false;
        }
        SnapshotCodec.apply(player, decoded);
        player.updateInventory();
        return true;
    }

    public ItemStack[][] preview(byte[] payload) {
        SnapshotCodec.Decoded d = SnapshotCodec.decode(payload);
        return d == null ? new ItemStack[0][] : new ItemStack[][]{d.storage(), d.enderChest()};
    }
}
