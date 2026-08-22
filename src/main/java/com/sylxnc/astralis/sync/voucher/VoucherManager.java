package com.sylxnc.astralis.sync.voucher;

import com.sylxnc.astralis.sync.Main;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Clickable voucher items. Identified via PersistentDataContainer, so they
 * survive inventory sync and can be redeemed with left/right click.
 */
public final class VoucherManager {

    public enum Type {
        ENDERCHEST_ROW("ec-row"),
        ENDERCHEST_MAX("ec-max");

        private final String id;

        Type(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        static Type fromId(String id) {
            for (Type t : values()) {
                if (t.id.equals(id)) {
                    return t;
                }
            }
            return null;
        }
    }

    private final Main plugin;
    private final NamespacedKey key;

    public VoucherManager(Main plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "voucher");
    }

    /** Builds a voucher item stack from config section "vouchers.<id>". */
    public ItemStack create(String id) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("vouchers." + id);
        if (section == null) {
            return null;
        }
        Material material = Material.matchMaterial(section.getString("material", "PAPER"));
        if (material == null) {
            material = Material.PAPER;
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        MiniMessage mm = MiniMessage.miniMessage();

        meta.displayName(mm.deserialize(section.getString("name", "<gold>Gutschein</gold>"))
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));

        List<Component> lore = new ArrayList<>();
        for (String line : section.getStringList("lore")) {
            lore.add(mm.deserialize(line).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(section.getBoolean("glow", true) ? Boolean.TRUE : null);
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING,
                section.getString("type", "ENDERCHEST_ROW") + "|" + id);
        item.setItemMeta(meta);
        return item;
    }

    /** Returns the voucher type when the item is one of ours, else null. */
    public Type identify(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String data = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (data == null) {
            return null;
        }
        String typeId = data.split("\\|")[0].toUpperCase(Locale.ROOT);
        try {
            return Type.valueOf(typeId);
        } catch (IllegalArgumentException e) {
            // maybe stored as raw action id
            return Type.fromId(data.split("\\|")[0]);
        }
    }

    /** Redeems the held voucher; returns true on success (item is consumed). */
    public boolean redeem(Player player, ItemStack item) {
        Type type = identify(item);
        if (type == null) {
            return false;
        }
        var redeemEvent = new com.sylxnc.astralis.sync.api.event.VoucherRedeemEvent(player, item);
        plugin.getServer().getPluginManager().callEvent(redeemEvent);
        if (redeemEvent.isCancelled()) {
            return false;
        }
        switch (type) {
            case ENDERCHEST_ROW -> {
                int newRows = plugin.getEnderChestManager().upgradeRow(player.getUniqueId());
                if (newRows < 0) {
                    plugin.getMessages().send(player, "voucher-ec-capped",
                            "<red>Deine Enderchest ist bereits auf dem Maximum!</red>");
                    return false;
                }
                plugin.getMessages().send(player, "voucher-ec-row",
                        "<green>Enderchest erweitert auf <white>{rows} Reihen</white>!</green>",
                        Map.of("rows", String.valueOf(newRows)));
            }
            case ENDERCHEST_MAX -> {
                int max = plugin.getEnderChestManager().maxRows();
                int current = plugin.getEnderChestManager().getRows(player.getUniqueId());
                if (current >= max) {
                    plugin.getMessages().send(player, "voucher-ec-capped",
                            "<red>Deine Enderchest ist bereits auf dem Maximum!</red>");
                    return false;
                }
                plugin.getDatabaseManager().setEnderChestRows(player.getUniqueId(), max);
                plugin.getEnderChestManager().cacheRows(player.getUniqueId(), max);
                plugin.getMessages().send(player, "voucher-ec-max",
                        "<green>Enderchest auf Maximum (<white>{rows}</white> Reihen) erweitert!</green>",
                        Map.of("rows", String.valueOf(max)));
            }
        }
        player.updateInventory();
        return true;
    }

    /** Gives a voucher of the given config id to an online player. */
    public void give(Player player, String id, int amount) {
        ItemStack voucher = create(id);
        if (voucher == null) {
            plugin.getLogger().warning("Unknown voucher id: " + id);
            return;
        }
        voucher.setAmount(Math.max(1, Math.min(64, amount)));
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(voucher);
        if (!overflow.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), voucher);
        }
        plugin.getMessages().send(player, "voucher-received", "<green>Du hast einen Gutschein erhalten!</green>");
    }
}
