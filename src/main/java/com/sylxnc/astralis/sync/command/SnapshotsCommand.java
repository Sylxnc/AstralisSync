package com.sylxnc.astralis.sync.command;

import com.sylxnc.astralis.sync.Main;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.UUID;

/**
 * /snapshots [spieler]        - GUI öffnen
 * /snapshots save             - manuellen Snapshot erstellen
 * /snapshots restore <id>     - eigenen Snapshot wiederherstellen
 */
public final class SnapshotsCommand implements TabExecutor {

    private final Main plugin;

    public SnapshotsCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Nur für Spieler.");
            return true;
        }
        if (!player.hasPermission("astralissync.snapshots") && !player.hasPermission("astralissync.admin")) {
            plugin.getMessages().send(player, "no-permission", "<red>Dazu hast du keine Rechte.</red>");
            return true;
        }

        // /snapshots [player] -> GUI
        if (args.length == 0 || (args.length == 1 && !args[0].equalsIgnoreCase("save"))) {
            String targetName = args.length == 1 ? args[0] : player.getName();
            resolveTarget(player, targetName);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "save" -> {
                plugin.getSnapshotManager().capture(player, "manual");
                plugin.getMessages().send(player, "snapshot-saved", "<green>Snapshot gespeichert!</green>");
            }
            case "restore" -> {
                long id = args.length >= 2 ? parseLong(args[1]) : -1;
                if (id <= 0) {
                    plugin.getMessages().send(player, "snapshots-usage",
                            "<red>Usage: /snapshots restore <id></red>");
                    return true;
                }
                boolean ok = plugin.getSnapshotManager().restore(player, id);
                if (ok) {
                    plugin.getMessages().send(player, "snapshot-restored",
                            "<green>Snapshot <white>#" + id + "</white> wiederhergestellt! Dein vorheriger Stand wurde gesichert.</green>");
                    plugin.getSyncService().savePlayer(player);
                } else {
                    plugin.getMessages().send(player, "snapshot-missing", "<red>Snapshot nicht gefunden.</red>");
                }
            }
            default -> plugin.getMessages().send(player, "snapshots-usage",
                    "<red>Usage: /snapshots [spieler|save|restore <id>]</red>");
        }
        return true;
    }

    private void resolveTarget(Player viewer, String targetName) {
        new BukkitRunnable() {
            @Override
            public void run() {
                UUID uuid = Bukkit.getPlayerExact(targetName) != null
                        ? Bukkit.getPlayerExact(targetName).getUniqueId()
                        : plugin.getDatabaseManager().lookupUuid(targetName);
                if (uuid == null) {
                    OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
                    uuid = offline.getUniqueId();
                }
                UUID resolved = uuid;
                Bukkit.getScheduler().runTask(plugin, () ->
                        new com.sylxnc.astralis.sync.snapshot.SnapshotBrowserMenu(
                                plugin, viewer, resolved, targetName, 0).openAsync());
            }
        }.runTaskAsynchronously(plugin);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.replace("#", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return List.of("save", "restore").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("restore")) {
            return List.of();
        }
        return List.of();
    }
}
