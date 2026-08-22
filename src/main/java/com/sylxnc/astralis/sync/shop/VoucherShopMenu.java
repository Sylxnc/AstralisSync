package com.sylxnc.astralis.sync.shop;

import com.sylxnc.astralis.sync.Main;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 27-slot voucher shop. Entries come from config section "shop":
 * each child id maps to a voucher id in "vouchers.<id>" and defines
 * cost-type (ITEM|XP), cost-item, cost-amount and an optional GUI slot.
 */
public final class VoucherShopMenu implements com.sylxnc.astralis.sync.gui.GuiManager.Menu {

    private final Main plugin;
    private final Player player;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    /** Slot -> voucher id for buttons rendered into the current inventory. */
    private final Map<Integer, String> buyTargets = new HashMap<>();

    private Inventory inventory;

    public VoucherShopMenu(Main plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        loadEntries();
    }

    /** Opens the shop GUI (command context already runs on the main thread). */
    public void open() {
        render();
    }

    private void render() {
        inventory = Bukkit.createInventory(null, 27,
                plugin.getMessages().renderRaw("<gradient:#B14EFF:#00E0FF>Vouchershop</gradient> <dark_gray>·</dark_gray> <gray>Gutscheine</gray>"));
        buyTargets.clear();

        var gui = plugin.getGuiManager();
        int fallbackSlot = 10; // used when an entry has no configured slot
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            String voucherId = e.getKey();
            Entry entry = e.getValue();

            String materialName = null;
            ConfigurationSection vouchers = plugin.getConfig().getConfigurationSection("vouchers." + voucherId);
            if (vouchers != null) {
                materialName = vouchers.getString("material");
            }
            Material icon = Material.matchMaterial(materialName == null ? "" : materialName);
            if (icon == null) {
                icon = Material.ENDER_EYE;
            }

            List<String> lore = List.of(
                    "<gray>Kosten:</gray> " + costDisplay(entry),
                    "",
                    "<yellow>Klick zum Kaufen!</yellow>");

            int slot = entry.slot >= 0 && entry.slot < 27 ? entry.slot : -1;
            if (slot < 0) {
                while (fallbackSlot < 18 && inventory.getItem(fallbackSlot) != null) {
                    fallbackSlot++;
                }
                if (fallbackSlot >= 18) {
                    break; // no free slots left
                }
                slot = fallbackSlot;
            } else if (inventory.getItem(slot) != null) {
                continue; // slot collision - skip instead of overwriting another entry
            }

            inventory.setItem(slot, gui.button(icon,
                    "<gradient:#B14EFF:#00E0FF>" + prettyId(voucherId) + "</gradient>",
                    lore,
                    "buy:" + slot));
            buyTargets.put(slot, voucherId);
        }

        // Bottom bar: filler + close button (pattern from SnapshotBrowserMenu)
        ItemStack filler = gui.button(Material.GRAY_STAINED_GLASS_PANE, " ", List.of(), "noop");
        for (int i = 18; i < 27; i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, filler);
            }
        }
        inventory.setItem(22, gui.button(Material.BARRIER, "<red>Schließen", List.of(), "close:0"));

        plugin.getGuiManager().open(player, inventory, this);
    }

    @Override
    public Inventory inventory() {
        return inventory;
    }

    @Override
    public void onClick(Player clicker, String action, long arg) {
        switch (action) {
            case "noop" -> {
            }
            case "close" -> clicker.closeInventory();
            case "buy" -> {
                String voucherId = buyTargets.get((int) arg);
                if (voucherId != null && clicker.isOnline()) {
                    handleBuy(clicker, voucherId);
                }
            }
            default -> {
            }
        }
    }

    private void handleBuy(Player buyer, String voucherId) {
        Entry entry = entries.get(voucherId);
        if (entry == null) {
            return;
        }
        if (!canAfford(buyer, entry)) {
            plugin.getMessages().send(buyer, "shop-cant-afford",
                    "<red>Du hast nicht genügend Ressourcen dafür!</red>");
            return;
        }
        takePayment(buyer, entry);
        plugin.getVoucherManager().give(buyer, voucherId, 1);
        plugin.getMessages().send(buyer, "shop-bought",
                "<green>Gekauft! Du hast einen Gutschein erhalten.</green>");
    }

    private boolean canAfford(Player p, Entry entry) {
        if ("XP".equals(entry.costType)) {
            return p.getLevel() >= entry.costAmount;
        }
        Material mat = Material.matchMaterial(entry.costItem == null ? "" : entry.costItem);
        if (mat == null || !mat.isItem()) {
            return false;
        }
        return p.getInventory().containsAtLeast(new ItemStack(mat), entry.costAmount);
    }

    private void takePayment(Player p, Entry entry) {
        if ("XP".equals(entry.costType)) {
            p.giveExpLevels(-entry.costAmount);
            return;
        }
        Material mat = Material.matchMaterial(entry.costItem == null ? "" : entry.costItem);
        if (mat == null || !mat.isItem()) {
            return;
        }
        int remaining = entry.costAmount;
        ItemStack[] storage = p.getInventory().getStorageContents();
        for (int i = 0; i < storage.length && remaining > 0; i++) {
            ItemStack item = storage[i];
            if (item == null || item.getType() != mat) {
                continue;
            }
            int take = Math.min(item.getAmount(), remaining);
            remaining -= take;
            if (take >= item.getAmount()) {
                p.getInventory().setItem(i, null);
            } else {
                item.setAmount(item.getAmount() - take);
            }
        }
        p.updateInventory();
    }

    private String costDisplay(Entry entry) {
        if ("XP".equals(entry.costType)) {
            return "<aqua>" + entry.costAmount + " Level</aqua>";
        }
        Material mat = Material.matchMaterial(entry.costItem == null ? "" : entry.costItem);
        String name = mat != null
                ? prettyId(mat.name())
                : (entry.costItem != null ? entry.costItem : "ITEM");
        return "<gold>" + entry.costAmount + "x</gold> <white>" + name + "</white>";
    }

    private static String prettyId(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String word : raw.toLowerCase(Locale.ROOT).split("_")) {
            if (!word.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return sb.toString();
    }

    private void loadEntries() {
        entries.clear();
        ConfigurationSection shop = plugin.getConfig().getConfigurationSection("shop");
        if (shop == null) {
            return;
        }
        for (String id : shop.getKeys(false)) {
            ConfigurationSection sec = shop.getConfigurationSection(id);
            if (sec == null) {
                continue;
            }
            Entry entry = new Entry();
            String type = sec.getString("cost-type", "ITEM");
            entry.costType = type == null ? "ITEM" : type.trim().toUpperCase(Locale.ROOT);
            entry.costItem = sec.getString("cost-item", "DIAMOND");
            entry.costAmount = Math.max(1, sec.getInt("cost-amount", 16));
            entry.slot = sec.getInt("slot", -1);
            entries.put(id, entry);
        }
    }

    /** One purchasable entry: voucher id + payment definition. */
    private static final class Entry {
        String costType = "ITEM";
        String costItem = "DIAMOND";
        int costAmount = 16;
        int slot = -1;
    }
}
