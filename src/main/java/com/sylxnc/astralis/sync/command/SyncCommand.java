package com.sylxnc.astralis.sync.command;

import com.sylxnc.astralis.sync.Main;
import com.sylxnc.astralis.sync.SyncService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SyncCommand implements TabExecutor {

    private final Main plugin;
    private final SyncService syncService;

    public SyncCommand(Main plugin, SyncService syncService) {
        this.plugin = plugin;
        this.syncService = syncService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadSettings();
                plugin.getMessages().send((sender instanceof org.bukkit.entity.Player p) ? p : null,
                        "config-reloaded", "<green>Konfiguration neu geladen.</green>");
                sender.sendMessage("§a[AstralisSync] Konfiguration neu geladen.");
            }
            case "status" -> handleStatus(sender);
            case "save" -> handleSave(sender, args);
            case "purge" -> handlePurge(sender, args);
            case "voucher" -> handleVoucher(sender, args);
            case "ec" -> handleEnderChest(sender, args);
            default -> sendHelp(sender, label);
        }
        return true;
    }

    private void handleVoucher(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length < 2) {
            sender.sendMessage("§cUsage: /astralissync voucher <id> [spieler] [anzahl]");
            return;
        }
        String id = args[1];
        Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2]) : player;
        int amount = args.length >= 4 ? parseIntSafe(args[3], 1) : 1;
        if (target == null) {
            sender.sendMessage("§cSpieler nicht online: " + (args.length >= 3 ? args[2] : "?"));
            return;
        }
        plugin.getVoucherManager().give(target, id, amount);
        sender.sendMessage("§aGutschein '" + id + "' an " + target.getName() + " gegeben.");
    }

    private void handleEnderChest(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("upgrade")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cNur für Spieler.");
                return;
            }
            int newRows = plugin.getEnderChestManager().upgradeRow(player.getUniqueId());
            if (newRows < 0) {
                plugin.getMessages().send(player, "voucher-ec-capped", "<red>Deine Enderchest ist bereits auf dem Maximum!</red>");
            } else {
                plugin.getMessages().send(player, "voucher-ec-row",
                        "<green>Enderchest erweitert auf <white>{rows} Reihen</white>!</green>",
                        Map.of("rows", String.valueOf(newRows)));
            }
            return;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("set")) {
            Player target = Bukkit.getPlayerExact(args[2]);
            int rows = args.length >= 4 ? parseIntSafe(args[3], -1) : -1;
            if (target == null || rows < 1 || rows > plugin.getEnderChestManager().maxRows()) {
                sender.sendMessage("§cUsage: /astralissync ec set <spieler> <1-" + plugin.getEnderChestManager().maxRows() + ">");
                return;
            }
            plugin.getDatabaseManager().setEnderChestRows(target.getUniqueId(), rows);
            plugin.getEnderChestManager().cacheRows(target.getUniqueId(), rows);
            sender.sendMessage("§aEC-Rows von " + target.getName() + " auf " + rows + " gesetzt.");
            return;
        }
        sender.sendMessage("§cUsage: /astralissync ec upgrade|set");
    }

    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void handleStatus(CommandSender sender) {
        int online = Bukkit.getOnlinePlayers().size();
        sender.sendMessage("§6=== AstralisSync Status ===");
        sender.sendMessage("§7Server-ID: §f" + plugin.getConfig().getString("server-id", "unknown"));
        sender.sendMessage("§7MySQL: §f" + (plugin.getDatabaseManager().isConnected() ? "§averbunden" : "§cgetrennt"));
        sender.sendMessage("§7Redis: §f" + (plugin.getRedisManager().isConnected() ? "§averbunden" : "§cgetrennt"));
        sender.sendMessage("§7Online-Spieler: §f" + online);
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("§6=== AstralisSync ===");
        sender.sendMessage("§e/" + label + " status §8- §7Verbindungsstatus");
        sender.sendMessage("§e/" + label + " save [spieler] §8- §7Sofort speichern");
        sender.sendMessage("§e/" + label + " purge <spieler> §8- §7Daten zurücksetzen");
        sender.sendMessage("§e/" + label + " voucher <id> [spieler] [anzahl] §8- §7Gutschein geben");
        sender.sendMessage("§e/" + label + " ec upgrade|set <spieler> <rows> §8- §7Enderchest-Rows");
        sender.sendMessage("§e/snapshots §8- §7Snapshot-Historie (GUI)");
        sender.sendMessage("§e/invsee <spieler> §8- §7Inventar über Server hinweg");
        sender.sendMessage("§e/" + label + " reload §8- §7Config neu laden");
    }

    private void handleSave(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("§cSpieler nicht online: " + args[1]);
                return;
            }
            syncService.savePlayer(target);
            sender.sendMessage("§aSpeichere " + target.getName() + "...");
        } else {
            syncService.saveAllOnline();
            sender.sendMessage("§aSpeichere alle Online-Spieler...");
        }
    }

    private void handlePurge(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /astralissync purge <player>");
            return;
        }
        UUID uuid = Bukkit.getOfflinePlayer(args[1]).getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = plugin.getDatabaseManager().deleteData(uuid);
            plugin.getRedisManager().invalidateCache(uuid);
            plugin.getWebhookNotifier().dataPurged(args[1], ok);
            sender.sendMessage(ok ? "§aDaten von " + args[1] + " gelöscht." : "§cKeine Daten für " + args[1] + " gefunden.");
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            for (String sub : List.of("reload", "status", "save", "purge", "voucher", "ec")) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("save") || args[0].equalsIgnoreCase("purge"))) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                    completions.add(p.getName());
                }
            }
        }
        return completions;
    }
}
