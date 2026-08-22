package com.sylxnc.astralis.sync.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired before an ender chest row upgrade is granted (main thread).
 * Cancelling blocks the upgrade (the voucher is not consumed).
 */
public final class EnderChestUpgradeEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final int oldRows;
    private final int newRows;
    private boolean cancelled;

    public EnderChestUpgradeEvent(Player player, int oldRows, int newRows) {
        this.player = player;
        this.oldRows = oldRows;
        this.newRows = newRows;
    }

    public Player getPlayer() {
        return player;
    }

    public int getOldRows() {
        return oldRows;
    }

    public int getNewRows() {
        return newRows;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
