package com.sylxnc.astralis.sync.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Public developer API of AstralisSync.
 * <p>
 * Obtain the instance via {@link ApiProvider#get()}. All methods are safe to
 * call from any thread unless noted otherwise; Bukkit-bound operations are
 * scheduled onto the server main thread internally.
 */
public interface AstralisSyncApi {

    /* ---------------- ender chest ---------------- */

    /** Current purchased ender chest rows of the player (1-6). */
    int getEnderChestRows(UUID playerId);

    /** Configured maximum ender chest rows. */
    int getMaxEnderChestRows();

    /**
     * Grants one ender chest row.
     *
     * @return the new row count, or -1 when already at the configured maximum
     */
    int upgradeEnderChestRows(UUID playerId);

    /* ---------------- vouchers ---------------- */

    /** Gives a configured voucher (see config.yml "vouchers") to the player. */
    void giveVoucher(Player player, String voucherId, int amount);

    /** True when the item is an AstralisSync voucher. */
    boolean isVoucher(ItemStack item);

    /* ---------------- snapshots ---------------- */

    /** Captures a snapshot of the player's current state (persisted async). */
    void captureSnapshot(Player player, String cause);

    /**
     * Restores snapshot {@code snapshotId} onto the player.
     * Must be called from the server main thread. The player's current state
     * is captured as "pre-restore" before overwriting.
     *
     * @return false when the snapshot does not exist or a listener cancelled it
     */
    boolean restoreSnapshot(Player player, long snapshotId);

    /* ---------------- raw data access ---------------- */

    /**
     * Loads the latest stored snapshot payload (Redis cache first, then MySQL).
     * The bytes use the versioned binary format of SnapshotCodec (v3).
     */
    CompletableFuture<byte[]> getPlayerData(UUID playerId);

    /** Persists the player's current state to MySQL + Redis (async I/O). */
    void savePlayer(Player player);

    /* ---------------- misc ---------------- */

    /** The server-id configured for this backend server. */
    String getServerId();
}
