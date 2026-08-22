package com.sylxnc.astralis.sync.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Minimal menu framework: named menus with clickable buttons identified by a
 * PDC action key. One global listener routes clicks to the open menu.
 */
public final class GuiManager implements Listener {

    public static final String ACTION_KEY = "astralissync_action";
    /** PDC value prefix for actions carrying a numeric payload: "restore:42". */
    private static final String ACTION_FORMAT = "%s:%s";

    private final Plugin plugin;
    private final NamespacedKey actionKey;
    private final java.util.Map<java.util.UUID, Menu> openMenus = new java.util.concurrent.ConcurrentHashMap<>();

    public GuiManager(Plugin plugin) {
        this.plugin = plugin;
        this.actionKey = new NamespacedKey(plugin, ACTION_KEY);
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    public ItemStack button(Material material, String miniTitle, List<String> lore, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(miniTitle)
                    .decorationIfAbsent(net.kyori.adventure.text.format.TextDecoration.ITALIC,
                            net.kyori.adventure.text.format.TextDecoration.State.FALSE));
            if (lore != null && !lore.isEmpty()) {
                var mm = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
                meta.lore(lore.stream().map(l -> mm.deserialize(l).decorationIfAbsent(
                        net.kyori.adventure.text.format.TextDecoration.ITALIC,
                        net.kyori.adventure.text.format.TextDecoration.State.FALSE)).toList());
            }
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
            item.setItemMeta(meta);
        }
        return item;
    }

    public void open(Player player, Inventory inventory, Menu menu) {
        openMenus.put(player.getUniqueId(), menu);
        player.openInventory(inventory);
    }

    public void closeTracking(Player player) {
        openMenus.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Menu menu = openMenus.get(player.getUniqueId());
        if (menu == null || event.getClickedInventory() == null) {
            return;
        }
        ItemStack current = event.getCurrentItem();
        if (current == null || !current.hasItemMeta()) {
            return;
        }
        String action = current.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) {
            // Click inside a tracked menu without an action button - block item movement.
            if (event.getInventory().equals(event.getClickedInventory()) && menu.blockClicks()) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);

        int colon = action.indexOf(':');
        String name = colon >= 0 ? action.substring(0, colon) : action;
        long arg = colon >= 0 ? parseLongSafe(action.substring(colon + 1)) : -1L;
        Bukkit.getScheduler().runTask(plugin, () ->
                menu.onClick(player, name, arg));
    }

    @EventHandler
    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            Menu menu = openMenus.get(player.getUniqueId());
            if (menu != null && event.getInventory().equals(menu.inventory())) {
                menu.onClose(player);
                openMenus.remove(player.getUniqueId());
            }
        }
    }

    private static long parseLongSafe(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /** A single tracked menu instance shown to one player. */
    public interface Menu {
        Inventory inventory();

        /** @param action action id, @param arg numeric payload or -1 */
        void onClick(Player player, String action, long arg);

        default boolean blockClicks() {
            return true;
        }

        default void onClose(Player player) {
        }
    }
}
