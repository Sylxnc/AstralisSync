package com.sylxnc.astralis.sync.command;

import com.sylxnc.astralis.sync.Main;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/**
 * /vouchershop - opens the voucher shop GUI (27 slots).
 *
 * Self-contained: register it from Main's integration point with ONE line:
 * <pre>
 *     getCommand("vouchershop").setExecutor(new com.sylxnc.astralis.sync.command.VoucherShopCommand(this));
 * </pre>
 * (TabExecutor covers tab completion too, so setTabCompleter is unnecessary.)
 */
public final class VoucherShopCommand implements TabExecutor {

    private final Main plugin;

    public VoucherShopCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Nur für Spieler.");
            return true;
        }
        if (!player.hasPermission("astralissync.shop") && !player.hasPermission("astralissync.admin")) {
            plugin.getMessages().send(player, "no-permission", "<red>Dazu hast du keine Rechte.</red>");
            return true;
        }
        new com.sylxnc.astralis.sync.shop.VoucherShopMenu(plugin, player).open();
        return true;
    }

    @Override
    public java.util.List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return java.util.List.of();
    }
}
