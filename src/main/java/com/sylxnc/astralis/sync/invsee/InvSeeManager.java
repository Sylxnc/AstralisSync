package com.sylxnc.astralis.sync.invsee;

import com.sylxnc.astralis.sync.Main;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-server /invsee.
 * <ul>
 *   <li>Local players: live-editable mirror inventory, changes are written
 *       back to the player every 2s and on close.</li>
 *   <li>Remote players: read-only view from the latest Redis cache; the target
 *       server is asked to save so the cache is refreshed.</li>
 * </ul>
 */
public final class InvSeeManager {

    private final Main plugin;
    private final Map<UUID, LiveMirror> liveMirrors = new ConcurrentHashMap<>();

    public InvSeeManager(Main plugin) {
        this.plugin = plugin;
    }

    public void open(Player viewer, String targetName) {
        Player local = Bukkit.getPlayerExact(targetName);
        if (local != null) {
            openLive(viewer, local);
            return;
        }
        openRemote(viewer, targetName);
    }

    /* ---------------- local live view ---------------- */

    private void openLive(Player viewer, Player target) {
        Inventory mirror = Bukkit.createInventory(new InvSeeHolder(target.getUniqueId(), true),
                45,
                plugin.getMessages().renderRaw("<gradient:#00FFA3:#00E0FF>InvSee</gradient> <dark_gray>·</dark_gray> <green>" + target.getName() + "</green> <yellow>(Live)</yellow>"));

        ItemStack[] storage = target.getInventory().getStorageContents();
        for (int i = 0; i < 36 && i < storage.length; i++) {
            mirror.setItem(i, storage[i] == null ? null : storage[i].clone());
        }
        // Armor + offhand in the last row
        ItemStack[] armor = target.getInventory().getArmorContents();
        for (int i = 0; i < 4 && i < armor.length; i++) {
            mirror.setItem(40 - i, armor[i] == null ? null : armor[i].clone());
        }
        mirror.setItem(36, target.getInventory().getItemInOffHand());

        liveMirrors.put(target.getUniqueId(), new LiveMirror(viewer.getUniqueId(), mirror));
        plugin.getGuiManager().open(viewer, mirror, new InvSeeMenu(target.getUniqueId(), target.getName(), true));
        startMirrorTask(target);
    }

    private void startMirrorTask(Player target) {
        new BukkitRunnable() {
            @Override
            public void run() {
                LiveMirror mirror = liveMirrors.get(target.getUniqueId());
                if (mirror == null || !target.isOnline()) {
                    liveMirrors.remove(target.getUniqueId());
                    cancel();
                    return;
                }
                // write viewer changes back to the player
                for (int i = 0; i < 36; i++) {
                    ItemStack item = mirror.inventory().getItem(i);
                    target.getInventory().setItem(i, item == null ? null : item.clone());
                }
                target.updateInventory();
            }
        }.runTaskTimer(plugin, 40L, 40L);
    }

    /* ---------------- remote read-only view ---------------- */

    private void openRemote(Player viewer, String targetName) {
        plugin.getSyncService().loadRemotePayload(viewer, targetName, (uuid, payload) -> {
            if (!viewer.isOnline()) {
                return;
            }
            if (payload == null) {
                plugin.getMessages().send(viewer, "invsee-not-found",
                        "<red>Keine Daten für <white>{name}</white> gefunden.</red>", java.util.Map.of("name", targetName));
                return;
            }
            var decoded = com.sylxnc.astralis.sync.SnapshotCodec.decode(payload);
            if (decoded == null) {
                plugin.getMessages().send(viewer, "invsee-not-found",
                        "<red>Keine Daten für <white>{name}</white> gefunden.</red>", java.util.Map.of("name", targetName));
                return;
            }
            Inventory view = Bukkit.createInventory(new InvSeeHolder(uuid, false), 45,
                    plugin.getMessages().renderRaw("<gradient:#00FFA3:#00E0FF>InvSee</gradient> <dark_gray>·</dark_gray> <gray>" + targetName + "</gray> <red>(Remote)</red>"));
            ItemStack[] storage = decoded.storage();
            for (int i = 0; i < 36 && i < storage.length; i++) {
                view.setItem(i, storage[i] == null ? null : storage[i].clone());
            }
            ItemStack[] armor = decoded.armor();
            for (int i = 0; i < 4 && i < armor.length; i++) {
                view.setItem(40 - i, armor[i] == null ? null : armor[i].clone());
            }
            view.setItem(36, decoded.offHand());
            plugin.getGuiManager().open(viewer, view, new InvSeeMenu(uuid, targetName, false));
        });
    }

    public void handleClose(Player viewer, Inventory closed) {
        if (closed.getHolder() instanceof InvSeeHolder holder && holder.live()) {
            liveMirrors.remove(holder.ownerId());
        }
    }

    public boolean isTracked(Inventory inventory) {
        return inventory.getHolder() instanceof InvSeeHolder;
    }

    private record LiveMirror(java.util.UUID viewerId, Inventory inventory) {
    }
}
