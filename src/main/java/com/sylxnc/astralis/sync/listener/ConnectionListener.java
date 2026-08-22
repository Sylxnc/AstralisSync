package com.sylxnc.astralis.sync.listener;

import com.sylxnc.astralis.sync.Main;
import com.sylxnc.astralis.sync.SyncService;
import com.sylxnc.astralis.sync.enderchest.EnderChestHolder;
import com.sylxnc.astralis.sync.invsee.InvSeeHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Login lifecycle (lock, load, quit-save), pre-death snapshots,
 * voucher redemption and custom inventory close handling.
 */
public final class ConnectionListener implements Listener {

    private final Main plugin;
    private final SyncService syncService;
    private final ConcurrentHashMap<UUID, Boolean> lockedPlayers = new ConcurrentHashMap<>();

    public ConnectionListener(Main plugin, SyncService syncService) {
        this.plugin = plugin;
        this.syncService = syncService;
    }

    /* ------------------------------------------------------------------
     * Login lifecycle
     * ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID uuid = event.getUniqueId();
        if (!syncService.acquireLock(uuid)) {
            plugin.getLogger().warning("Denied join of " + event.getName() + ": data locked by another server.");
            plugin.getWebhookNotifier().lockConflict(event.getName());
            event.setLoginResult(AsyncPlayerPreLoginEvent.Result.KICK_OTHER);
            event.kickMessage(Component.text(
                    "Deine Daten werden gerade auf einem anderen Server gespeichert.\nBitte warte einen Moment!",
                    NamedTextColor.RED));
            return;
        }
        lockedPlayers.put(uuid, true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        plugin.getMessages().title(player,
                "<gradient:#B14EFF:#00E0FF>AstralisSync</gradient>",
                "<gray>Deine Daten wurden geladen.</gray>");

        if (!lockedPlayers.containsKey(uuid)) {
            // Reload or direct spawn without PreLogin - try to lock anyway.
            if (!syncService.acquireLock(uuid)) {
                player.kick(Component.text("Deine Spielerdaten sind gerade gesperrt.", NamedTextColor.RED));
                return;
            }
            lockedPlayers.put(uuid, true);
        }
        // load rows into cache early so saves use the right EC size
        Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> plugin.getEnderChestManager().getRows(uuid));
        syncService.loadAndApply(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lockedPlayers.remove(uuid);
        syncService.handleQuit(event.getPlayer());
    }

    /* ------------------------------------------------------------------
     * Pre-death snapshots
     * ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFatalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !player.isOnline()) {
            return;
        }
        double remaining = player.getHealth() - event.getFinalDamage();
        if (remaining <= 0.0 && !player.isDead()) {
            plugin.getSnapshotManager().capture(player, "death");
        }
    }

    /* ------------------------------------------------------------------
     * Vouchers (left / right click)
     * ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND && event.getHand() != EquipmentSlot.OFF_HAND) {
            return;
        }
        String action = event.getAction().name();
        boolean click = action.contains("LEFT_CLICK") || action.contains("RIGHT_CLICK");
        if (!click) {
            return;
        }
        if (plugin.getVoucherManager().identify(item) == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (plugin.getVoucherManager().redeem(player, item)) {
            item.setAmount(item.getAmount() - 1); // consume exactly one voucher
        }
    }

    /* ------------------------------------------------------------------
     * Custom inventory close handling (EC mirror, InvSee mirrors)
     * ------------------------------------------------------------------ */

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory closed = event.getInventory();
        if (closed.getHolder() instanceof EnderChestHolder) {
            plugin.getEnderChestManager().handleClose(player, closed);
        } else if (closed.getHolder() instanceof InvSeeHolder) {
            plugin.getInvSeeManager().handleClose(player, closed);
        }
    }
}
