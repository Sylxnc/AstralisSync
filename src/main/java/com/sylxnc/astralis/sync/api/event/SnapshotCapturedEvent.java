package com.sylxnc.astralis.sync.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired (async) after a player snapshot was captured and persisted.
 */
public final class SnapshotCapturedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String playerName;
    private final String cause;

    public SnapshotCapturedEvent(UUID playerId, String playerName, String cause) {
        super(true); // async
        this.playerId = playerId;
        this.playerName = playerName;
        this.cause = cause;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    /** death | quit | manual | pre-restore */
    public String getCause() {
        return cause;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
