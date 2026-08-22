package com.sylxnc.astralis.sync.enderchest;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/** Marks an inventory as our custom ender chest view. */
public record EnderChestHolder(UUID ownerId) implements InventoryHolder {

    @Override
    public Inventory getInventory() {
        throw new UnsupportedOperationException("holder only");
    }
}
