package com.sylxnc.astralis.sync.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/**
 * Fired before a voucher is redeemed via click (main thread).
 * Cancelling prevents redemption; the voucher item stays in the inventory.
 */
public final class VoucherRedeemEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final ItemStack voucherItem;
    private boolean cancelled;

    public VoucherRedeemEvent(Player player, ItemStack voucherItem) {
        this.player = player;
        this.voucherItem = voucherItem;
    }

    public Player getPlayer() {
        return player;
    }

    public ItemStack getVoucherItem() {
        return voucherItem.clone();
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
