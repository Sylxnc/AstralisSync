package com.sylxnc.astralis.sync.task;

import com.sylxnc.astralis.sync.SyncService;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.UUID;

/**
 * Renews the distributed lock of every online player so the 90s Redis TTL
 * never expires while they are actually playing here.
 */
public final class LockRenewTask extends BukkitRunnable {

    private final SyncService syncService;

    public LockRenewTask(SyncService syncService) {
        this.syncService = syncService;
    }

    @Override
    public void run() {
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (!syncService.renewLock(uuid)) {
                Bukkit.getLogger().warning("[AstralisSync] Lost data lock for "
                        + player.getName() + "! Forcing save+relock.");
                syncService.savePlayer(player);
            }
        }
    }
}
