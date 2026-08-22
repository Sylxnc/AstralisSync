package com.sylxnc.astralis.sync.api;

import com.sylxnc.astralis.sync.Main;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Default implementation backed by the plugin's managers. */
final class ApiImpl implements AstralisSyncApi {

    private final Main plugin;

    ApiImpl(Main plugin) {
        this.plugin = plugin;
    }

    /* ---------------- ender chest ---------------- */

    @Override
    public int getEnderChestRows(UUID playerId) {
        return plugin.getEnderChestManager().getRows(playerId);
    }

    @Override
    public int getMaxEnderChestRows() {
        return plugin.getEnderChestManager().maxRows();
    }

    @Override
    public int upgradeEnderChestRows(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        int current = getEnderChestRows(playerId);
        int target = current + 1;
        if (target > getMaxEnderChestRows()) {
            return -1;
        }
        if (online != null && Bukkit.isPrimaryThread()) {
            var event = new com.sylxnc.astralis.sync.api.event.EnderChestUpgradeEvent(online, current, target);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return -1;
            }
        }
        plugin.getDatabaseManager().setEnderChestRows(playerId, target);
        plugin.getEnderChestManager().cacheRows(playerId, target);
        return target;
    }

    /* ---------------- vouchers ---------------- */

    @Override
    public void giveVoucher(Player player, String voucherId, int amount) {
        plugin.getVoucherManager().give(player, voucherId, amount);
    }

    @Override
    public boolean isVoucher(ItemStack item) {
        return plugin.getVoucherManager().identify(item) != null;
    }

    /* ---------------- snapshots ---------------- */

    @Override
    public void captureSnapshot(Player player, String cause) {
        plugin.getSnapshotManager().capture(player, cause == null ? "api" : cause);
    }

    @Override
    public boolean restoreSnapshot(Player player, long snapshotId) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("restoreSnapshot must be called on the main thread");
        }
        var event = new com.sylxnc.astralis.sync.api.event.SnapshotRestoreEvent(player, snapshotId);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return false;
        }
        boolean ok = plugin.getSnapshotManager().restore(player, snapshotId);
        if (ok) {
            plugin.getWebhookNotifier().snapshotRestored(player, snapshotId);
            savePlayer(player);
        }
        return ok;
    }

    /* ---------------- raw data access ---------------- */

    @Override
    public CompletableFuture<byte[]> getPlayerData(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> {
            byte[] cached = plugin.getRedisManager().getCachedData(playerId);
            if (cached != null) {
                return cached;
            }
            byte[] stored = plugin.getDatabaseManager().loadData(playerId);
            if (stored != null) {
                plugin.getRedisManager().cacheData(playerId, stored);
            }
            return stored;
        });
    }

    @Override
    public void savePlayer(Player player) {
        plugin.getSyncService().savePlayer(player);
    }

    /* ---------------- misc ---------------- */

    @Override
    public String getServerId() {
        return plugin.getConfig().getString("server-id", "unknown");
    }
}
