package com.sylxnc.astralis.sync.task;

import com.sylxnc.astralis.sync.SyncService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.UUID;

/**
 * Periodically persists every online player so a crash never costs more than
 * one interval of progress.
 */
public final class SaveTask extends BukkitRunnable {

    private final SyncService syncService;

    public SaveTask(SyncService syncService) {
        this.syncService = syncService;
    }

    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isDead() && player.isOnline()) {
                syncService.savePlayer(player);
            }
        }
    }
}
