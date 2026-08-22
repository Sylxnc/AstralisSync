package com.sylxnc.astralis.sync.invsee;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/** Holder for invsee inventories; live = editable mirror of an online player. */
public record InvSeeHolder(UUID ownerId, boolean live) implements InventoryHolder {

    @Override
    public Inventory getInventory() {
        throw new UnsupportedOperationException("holder only");
    }
}
