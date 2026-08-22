package com.sylxnc.astralis.sync.command;

import com.sylxnc.astralis.sync.Main;
import com.sylxnc.astralis.sync.dataio.DataExporter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * /syncexport export <spieler> [ordner]  - Spielerdaten als JSON exportieren
 * /syncexport import <dateiname>         - Exportdatei auf sich selbst anwenden
 * <p>
 * Dateien liegen ausschließlich unter plugins/AstralalisSync/exports/
 * (Path-Traversal-Schutz). Import gilt immer für den ausführenden,
 * online befindlichen Spieler.
 */
public final class ExportCommand implements TabExecutor {

    private static final String PERMISSION = "astralissync.export";

    private final Main plugin;
    private final DataExporter dataio;

    public ExportCommand(Main plugin) {
        this.plugin = plugin;
        this.dataio = new DataExporter(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage("§cDazu hast du keine Rechte.");
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "export" -> handleExport(sender, args);
            case "import" -> handleImport(sender, args);
            default -> sendUsage(sender);
        }
        return true;
    }

    /* ------------------------------------------------------------------
     * /syncexport export <spieler> [ordner]
     * ------------------------------------------------------------------ */

    private void handleExport(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /syncexport export <spieler> [ordner]");
            return;
        }
        String targetName = args[1];

        Path dir = dataio.exportsDirectory();
        if (args.length >= 3) {
            Path custom = resolveInsideExports(args[2]);
            if (custom == null) {
                sender.sendMessage("§cUngültiger Ordner: darf nicht außerhalb von "
                        + "plugins/AstralalisSync/exports/ zeigen.");
                return;
            }
            dir = custom;
        }

        // File name is derived server-side from sanitized player name + timestamp.
        String safeName = sanitizeFileName(targetName);
        String fileName = safeName + "-" + System.currentTimeMillis() + ".json";
        Path outFile = dir.resolve(fileName);

        sender.sendMessage("§7Starte Export für §e" + targetName + "§7...");
        dataio.export(sender, targetName, outFile);
    }

    /* ------------------------------------------------------------------
     * /syncexport import <dateiname>
     * ------------------------------------------------------------------ */

    private void handleImport(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cImport nur als Spieler möglich (die Daten werden "
                    + "auf dich angewendet).");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /syncexport import <dateiname>");
            return;
        }

        Path file = resolveImportFile(args[1]);
        if (file == null) {
            sender.sendMessage("§cUngültiger Dateiname: darf keine Pfadanteile "
                    + "(../, \\, absolute Pfade) enthalten.");
            return;
        }
        if (!Files.isRegularFile(file)) {
            sender.sendMessage("§cDatei nicht gefunden: §eexports/" + args[1]
                    + "§c. Nutze Tab-Complete für verfügbare Dateien.");
            return;
        }

        player.sendMessage("§7Starte Import von §e" + file.getFileName() + "§7...");
        dataio.importData(player, player, file);
    }

    /* ------------------------------------------------------------------
     * Path safety
     * ------------------------------------------------------------------ */

    /**
     * Resolves {@code name} strictly inside plugins/AstralalisSync/exports/.
     * Rejects path separators, parent references, leading dots and anything
     * that normalizes outside the exports directory.
     *
     * @return absolute path inside exports/, or null when rejected
     */
    private Path resolveImportFile(String name) {
        String cleaned = sanitizeFileName(name);
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            Path exportsRoot = dataio.exportsDirectory().toAbsolutePath().normalize();
            Path candidate = exportsRoot.resolve(cleaned).normalize();
            if (!candidate.startsWith(exportsRoot)) {
                return null;
            }
            // Extra hard line: no nested paths at all for imports.
            if (!candidate.getParent().equals(exportsRoot)) {
                return null;
            }
            return candidate;
        } catch (Exception e) {
            return null;
        }
    }

    /** Optional subdirectory for export targets; still jailed to exports/. */
    private Path resolveInsideExports(String subDir) {
        try {
            Path exportsRoot = dataio.exportsDirectory().toAbsolutePath().normalize();
            Path candidate = exportsRoot.resolve(subDir).normalize();
            if (!candidate.startsWith(exportsRoot)) {
                return null;
            }
            return candidate;
        } catch (Exception e) {
            return null;
        }
    }

    /** Strips everything that could escape a filename or smuggle separators. */
    private static String sanitizeFileName(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        // Remove drive letters, separators, parent refs and control chars.
        s = s.replace("\\", "_");
        while (s.contains("..")) {
            s = s.replace("..", "_");
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            sb.append(allowed ? c : '_');
        }
        s = sb.toString();
        // No hidden files / no bare dots.
        while (s.startsWith(".")) {
            s = s.substring(1);
        }
        if (s.length() > 64) {
            s = s.substring(0, 64);
        }
        return s;
    }

    /* ------------------------------------------------------------------
     * Usage + tab completion
     * ------------------------------------------------------------------ */

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("§6=== AstralisSync Data-Export ===");
        sender.sendMessage("§e/syncexport export <spieler> [ordner] §7- Daten als JSON sichern");
        sender.sendMessage("§e/syncexport import <dateiname> §7- Daten auf dich anwenden");
        sender.sendMessage("§7Dateien: §fplugins/AstralalisSync/exports/");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            return prefixMatch(args[0], List.of("export", "import"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("import")) {
            return completeExportFileNames(args[1]);
        }
        return List.of();
    }

    /** Suggests *.json files inside the exports directory only. */
    private List<String> completeExportFileNames(String prefix) {
        Path dir = dataio.exportsDirectory();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<String> names = new ArrayList<>();
            files.filter(Files::isRegularFile)
                 .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                 .forEach(p -> names.add(p.getFileName().toString()));
            return prefixMatch(prefix, names);
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<String> prefixMatch(String prefix, List<String> options) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(option);
            }
        }
        return out;
    }

    /* unused-warning guard for future integrators reading this class */
    @SuppressWarnings("unused")
    private Main pluginRef() {
        return plugin;
    }
}
