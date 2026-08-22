package com.sylxnc.astralis.sync.enderchest;

import com.sylxnc.astralis.sync.Main;
import com.sylxnc.astralis.sync.db.DatabaseManager;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Row-based custom ender chests. Vanilla ender chest inventories are always 27
 * slots; upgrades are therefore implemented with a custom Inventory that is
 * mirrored back into the vanilla 27-slot storage on close.
 */
public final class EnderChestManager {

    private final Main plugin;
    private final DatabaseManager database;
    /** Players that currently have a custom (upgraded) EC open. */
    private final Map<UUID, Inventory> openCustom = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> rowCache = new ConcurrentHashMap<>();

    public EnderChestManager(Main plugin) {
        this.plugin = plugin;
        this.database = plugin.getDatabaseManager();
    }

    public int defaultRows() {
        return Math.max(1, Math.min(3, plugin.getConfig().getInt("enderchest.default-rows", 3)));
    }

    public int maxRows() {
        return Math.max(defaultRows(), Math.min(6, plugin.getConfig().getInt("enderchest.max-rows", 6)));
    }

    public int getRows(UUID uuid) {
        return rowCache.computeIfAbsent(uuid, id -> {
            int stored = database.getEnderChestRows(id);
            return stored > 0 ? Math.min(stored, maxRows()) : defaultRows();
        });
    }

    public void cacheRows(UUID uuid, int rows) {
        rowCache.put(uuid, Math.max(1, rows));
    }

    public void forget(UUID uuid) {
        rowCache.remove(uuid);
    }

    /** Opens the (possibly upgraded) ender chest view for the player. */
    public void open(Player player) {
        int rows = getRows(player.getUniqueId());
        if (rows <= 3) {
            player.openInventory(player.getEnderChest());
            return;
        }
        Inventory custom = Bukkit.createInventory(new EnderChestHolder(player.getUniqueId()),
                rows * 9,
                plugin.getMessages().renderRaw("<gradient:#B14EFF:#00E0FF>Enderchest</gradient> <dark_gray>·</dark_gray> <gray>Reihe " + rows + "</gray>"));
        ItemStack[] vanilla = player.getEnderChest().getContents();
        for (int i = 0; i < Math.min(vanilla.length, custom.getSize()); i++) {
            custom.setItem(i, vanilla[i]);
        }
        openCustom.put(player.getUniqueId(), custom);
        player.openInventory(custom);
    }

    /**
     * Called on inventory close: mirrors custom contents back into the vanilla
     * 27-slot ender chest storage so the normal save path persists them.
     */
    public void handleClose(Player player, Inventory closed) {
        UUID uuid = player.getUniqueId();
        Inventory custom = openCustom.remove(uuid);
        if (custom == null || closed == null || !closed.equals(custom)) {
            return;
        }
        ItemStack[] vanilla = player.getEnderChest().getContents();
        int copyable = Math.min(closed.getSize(), vanilla.length);
        for (int i = 0; i < copyable; i++) {
            vanilla[i] = closed.getItem(i);
        }
        player.getEnderChest().setContents(vanilla);
    }

    /** Grants +1 row if possible. Returns the new row count or -1 when capped. */
    public int upgradeRow(UUID uuid) {
        int current = getRows(uuid);
        int target = current + 1;
        if (target > maxRows()) {
            return -1;
        }
        database.setEnderChestRows(uuid, target);
        rowCache.put(uuid, target);
        return target;
    }
}
