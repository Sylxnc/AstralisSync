package com.sylxnc.astralis.sync.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired before a snapshot restore is applied to the player (main thread).
 * Cancelling blocks the restore.
 */
public final class SnapshotRestoreEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final long snapshotId;
    private boolean cancelled;

    public SnapshotRestoreEvent(Player player, long snapshotId) {
        this.player = player;
        this.snapshotId = snapshotId;
    }

    public Player getPlayer() {
        return player;
    }

    public long getSnapshotId() {
        return snapshotId;
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
