package com.sylxnc.astralis.sync;

import com.sylxnc.astralis.sync.command.SyncCommand;
import com.sylxnc.astralis.sync.db.DatabaseManager;
import com.sylxnc.astralis.sync.listener.ConnectionListener;
import com.sylxnc.astralis.sync.redis.RedisManager;
import com.sylxnc.astralis.sync.task.LockRenewTask;
import com.sylxnc.astralis.sync.task.SaveTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * AstralisSync - syncs player data (inventory, ender chest, xp, ...) across
 * multiple backend servers using MySQL as persistent storage and Redis for
 * caching and cross-server messaging.
 */
public final class Main extends JavaPlugin {

    private DatabaseManager databaseManager;
    private RedisManager redisManager;
    private SyncService syncService;
    private Messages messages;
    private com.sylxnc.astralis.sync.notify.WebhookNotifier webhookNotifier;
    private com.sylxnc.astralis.sync.progress.AdvancementSync advancementSync;
    private com.sylxnc.astralis.sync.progress.StatisticsSync statisticsSync;
    private com.sylxnc.astralis.sync.enderchest.EnderChestManager enderChestManager;
    private com.sylxnc.astralis.sync.voucher.VoucherManager voucherManager;
    private com.sylxnc.astralis.sync.snapshot.SnapshotManager snapshotManager;
    private com.sylxnc.astralis.sync.gui.GuiManager guiManager;
    private com.sylxnc.astralis.sync.invsee.InvSeeManager invSeeManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.messages = new Messages(this);

        this.databaseManager = new DatabaseManager(this);
        if (!databaseManager.connect()) {
            getLogger().severe("Could not connect to MySQL. Disabling plugin!");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.redisManager = new RedisManager(this);
        if (!redisManager.connect()) {
            databaseManager.close();
            getLogger().severe("Could not connect to Redis. Disabling plugin!");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.guiManager = new com.sylxnc.astralis.sync.gui.GuiManager(this);
        guiManager.register();

        this.enderChestManager = new com.sylxnc.astralis.sync.enderchest.EnderChestManager(this);
        this.voucherManager = new com.sylxnc.astralis.sync.voucher.VoucherManager(this);

        this.snapshotManager = new com.sylxnc.astralis.sync.snapshot.SnapshotManager(this);
        snapshotManager.init();

        this.invSeeManager = new com.sylxnc.astralis.sync.invsee.InvSeeManager(this);

        com.sylxnc.astralis.sync.api.ApiProvider.register(com.sylxnc.astralis.sync.api.ApiFactory.create(this));

        this.webhookNotifier = new com.sylxnc.astralis.sync.notify.WebhookNotifier(this);
        this.advancementSync = new com.sylxnc.astralis.sync.progress.AdvancementSync(this);
        this.statisticsSync = new com.sylxnc.astralis.sync.progress.StatisticsSync(this);

        this.syncService = new SyncService(this, databaseManager, redisManager);
        redisManager.registerMessageListener((type, sourceServer, uuid) -> {
            if ("SAVE".equals(type)) {
                getLogger().fine(() -> "Player data saved by " + sourceServer + " for " + uuid);
            } else if ("REQ_SAVE".equals(type) && redisManager.isOwner(uuid)) {
                Player owner = Bukkit.getPlayer(uuid);
                if (owner != null && owner.isOnline()) {
                    saveOnlineQuietly(owner);
                }
            }
        });

        Bukkit.getPluginManager().registerEvents(new ConnectionListener(this, syncService), this);

        long autosaveInterval = Math.max(100L, getConfig().getLong("autosave-interval-ticks", 6000L));
        new SaveTask(syncService).runTaskTimer(this, autosaveInterval, autosaveInterval);

        long renewInterval = 20L * 30; // well below the 90s lock TTL
        new LockRenewTask(syncService).runTaskTimer(this, renewInterval, renewInterval);

        var command = getCommand("astralissync");
        if (command != null) {
            SyncCommand executor = new SyncCommand(this, syncService);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        var snapshotsCommand = getCommand("snapshots");
        if (snapshotsCommand != null) {
            var executor = new com.sylxnc.astralis.sync.command.SnapshotsCommand(this);
            snapshotsCommand.setExecutor(executor);
            snapshotsCommand.setTabCompleter(executor);
        }

        var invseeCommand = getCommand("invsee");
        if (invseeCommand != null) {
            invseeCommand.setExecutor(new com.sylxnc.astralis.sync.command.InvSeeCommand(this));
        }

        var shopCommand = getCommand("vouchershop");
        if (shopCommand != null) {
            shopCommand.setExecutor(new com.sylxnc.astralis.sync.command.VoucherShopCommand(this));
        }

        var exportCommand = getCommand("syncexport");
        if (exportCommand != null) {
            exportCommand.setExecutor(new com.sylxnc.astralis.sync.command.ExportCommand(this));
        }

        // PlaceholderAPI expansion (no-op when PAPI is absent)
        try {
            com.sylxnc.astralis.sync.placeholder.SyncExpansion.register(this);
        } catch (NoClassDefFoundError ignored) {
            getLogger().info("PlaceholderAPI not present - placeholders disabled.");
        }

        getLogger().info("AstralisSync enabled (server-id: "
                + getConfig().getString("server-id", "unknown") + ").");
    }

    @Override
    public void onDisable() {
        com.sylxnc.astralis.sync.api.ApiProvider.unregister();
        if (guiManager != null) {
            guiManager.unregister();
        }
        if (syncService != null) {
            // Persist everyone still online so nothing is lost on shutdown.
            for (Player player : Bukkit.getOnlinePlayers()) {
                try {
                    syncService.savePlayerBlocking(player);
                } catch (Exception e) {
                    getLogger().warning("Final save failed for " + player.getName() + ": " + e.getMessage());
                }
            }
        }
        if (redisManager != null) {
            redisManager.close();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("AstralisSync disabled.");
    }

    private void saveOnlineQuietly(Player player) {
        try {
            syncService.savePlayer(player);
        } catch (Exception e) {
            getLogger().warning("Requested save failed for " + player.getName() + ": " + e.getMessage());
        }
    }

    public void reloadSettings() {
        reloadConfig();
        databaseManager.reload();
        redisManager.reload();
    }

    public Messages getMessages() {
        return messages;
    }

    public com.sylxnc.astralis.sync.enderchest.EnderChestManager getEnderChestManager() {
        return enderChestManager;
    }

    public com.sylxnc.astralis.sync.voucher.VoucherManager getVoucherManager() {
        return voucherManager;
    }

    public com.sylxnc.astralis.sync.snapshot.SnapshotManager getSnapshotManager() {
        return snapshotManager;
    }

    public com.sylxnc.astralis.sync.gui.GuiManager getGuiManager() {
        return guiManager;
    }

    public com.sylxnc.astralis.sync.invsee.InvSeeManager getInvSeeManager() {
        return invSeeManager;
    }

    public com.sylxnc.astralis.sync.notify.WebhookNotifier getWebhookNotifier() {
        return webhookNotifier;
    }

    public com.sylxnc.astralis.sync.progress.AdvancementSync getAdvancementSync() {
        return advancementSync;
    }

    public com.sylxnc.astralis.sync.progress.StatisticsSync getStatisticsSync() {
        return statisticsSync;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public RedisManager getRedisManager() {
        return redisManager;
    }

    public SyncService getSyncService() {
        return syncService;
    }
}
