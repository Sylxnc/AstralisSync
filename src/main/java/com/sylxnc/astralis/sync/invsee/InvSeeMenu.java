package com.sylxnc.astralis.sync.invsee;

import com.sylxnc.astralis.sync.Main;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.UUID;

/** Menu adapter for invsee inventories (blocks clicks in remote views). */
public final class InvSeeMenu implements com.sylxnc.astralis.sync.gui.GuiManager.Menu {

    private final UUID ownerId;
    @SuppressWarnings("unused")
    private final String ownerName;
    private final boolean live;

    public InvSeeMenu(UUID ownerId, String ownerName, boolean live) {
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.live = live;
    }

    @Override
    public Inventory inventory() {
        // The real inventory is opened by InvSeeManager; tracking only needs identity.
        return Bukkit.createInventory(new InvSeeHolder(ownerId, live), 45);
    }

    @Override
    public void onClick(Player player, String action, long arg) {
        // no action buttons inside invsee
    }

    @Override
    public boolean blockClicks() {
        return !live;
    }
}
