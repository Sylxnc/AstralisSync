package com.sylxnc.astralis.sync.command;

import com.sylxnc.astralis.sync.Main;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /invsee <player> - live cross-server inventory view. */
public final class InvSeeCommand implements org.bukkit.command.CommandExecutor {

    private final Main plugin;

    public InvSeeCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Nur für Spieler.");
            return true;
        }
        if (!player.hasPermission("astralissync.invsee") && !player.hasPermission("astralissync.admin")) {
            plugin.getMessages().send(player, "no-permission", "<red>Dazu hast du keine Rechte.</red>");
            return true;
        }
        if (args.length < 1) {
            plugin.getMessages().send(player, "invsee-usage", "<red>Usage: /invsee <spieler></red>");
            return true;
        }
        plugin.getInvSeeManager().open(player, args[0]);
        return true;
    }
}
