package com.sylxnc.astralis.sync.dataio;

import com.sylxnc.astralis.sync.Main;
import com.sylxnc.astralis.sync.SnapshotCodec;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Admin migration I/O between networks.
 * <p>
 * Export: loads a player's stored snapshot from MySQL, decodes it and writes a
 * portable JSON file (Base64 of {@link ItemStack#serializeAsBytes()} per item).
 * <p>
 * Import: reads such a JSON file, validates it strictly, re-encodes it into the
 * exact v3 binary layout of {@link SnapshotCodec} (via {@link BinaryComposer})
 * and saves it as the target player's data, invalidating the Redis cache.
 * <p>
 * All file/DB I/O runs async; ItemStack reconstruction ({@code deserializeBytes}
 * builds CraftItemStacks) is done on the main thread in a single tick.
 */
public final class DataExporter {

    private static final int STORAGE_SIZE = 36;
    private static final int ARMOR_SIZE = 4;
    private static final int MAX_ENDER_CHEST_ROWS = 6;
    private static final String EXPORT_DIR = "exports";

    /** Single serialized item sanity cap (largest known item NBT is ~100 KB). */
    private static final int MAX_ITEM_BYTES = 256 * 1024;

    private final Main plugin;

    public DataExporter(Main plugin) {
        this.plugin = plugin;
    }

    public Path exportsDirectory() {
        return plugin.getDataFolder().toPath().resolve(EXPORT_DIR);
    }

    /* ==================================================================
     * Export
     * ================================================================== */

    /**
     * Resolves the target (online -> DB lookup -> OfflinePlayer), loads the
     * stored payload async, decodes it on the main thread (item deserialization)
     * and writes the JSON file async.
     *
     * @param reporter   progress/result receiver
     * @param playerName online name or last known name
     * @param outFile    destination file (already validated by caller)
     */
    public void export(org.bukkit.command.CommandSender reporter, String playerName, Path outFile) {
        new BukkitRunnable() {
            @Override
            public void run() {
                UUID uuid;
                Player online = Bukkit.getPlayerExact(playerName);
                if (online != null) {
                    uuid = online.getUniqueId();
                } else {
                    // Blocking JDBC - fine here, we are off the main thread.
                    uuid = plugin.getDatabaseManager().lookupUuid(playerName);
                    if (uuid == null) {
                        OfflinePlayer offline = Bukkit.getOfflinePlayer(playerName);
                        // getOfflinePlayer(name) may do a blocking web lookup; async-safe.
                        if (!offline.hasPlayedBefore() && !offline.isOnline()) {
                            report(reporter, "§cSpieler nicht gefunden: " + playerName);
                            return;
                        }
                        uuid = offline.getUniqueId();
                    }
                }
                final UUID resolvedUuid = uuid;

                byte[] payload = plugin.getDatabaseManager().loadData(resolvedUuid);
                if (payload == null) {
                    report(reporter, "§cKeine gespeicherten Daten für §e" + playerName
                            + "§c (" + resolvedUuid + ").");
                    return;
                }
                report(reporter, "§7Snapshot geladen (" + payload.length + " Bytes), dekodiere...");

                // decode() constructs ItemStacks -> must run on the main thread.
                Bukkit.getScheduler().runTask(plugin, () -> {
                    SnapshotCodec.Decoded d = SnapshotCodec.decode(payload);
                    if (d == null) {
                        report(reporter, "§cSnapshot ist beschädigt und kann nicht exportiert werden.");
                        return;
                    }

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            try {
                                Files.createDirectories(outFile.toAbsolutePath().getParent());
                                Files.writeString(outFile, toJson(d, resolvedUuid, playerName,
                                        payload.length), StandardCharsets.UTF_8);
                                long items = countItems(d);
                                report(reporter, "§aExport erfolgreich: §e" + outFile.toAbsolutePath()
                                        + " §7(" + items + " Items)");
                                report(reporter, "§aUUID: §e" + resolvedUuid);
                            } catch (IOException e) {
                                plugin.getLogger().log(Level.SEVERE, "Export failed for " + playerName, e);
                                report(reporter, "§cExport fehlgeschlagen: " + e.getMessage());
                            } catch (Exception e) {
                                plugin.getLogger().log(Level.SEVERE, "Export failed for " + playerName, e);
                                report(reporter, "§cExport fehlgeschlagen: " + e.getClass().getSimpleName());
                            }
                        }
                    }.runTaskAsynchronously(plugin);
                });
            }
        }.runTaskAsynchronously(plugin);
    }

    private static long countItems(SnapshotCodec.Decoded d) {
        return nonNull(d.storage()) + nonNull(d.armor())
                + (d.offHand() != null ? 1 : 0) + nonNull(d.enderChest());
    }

    private static int nonNull(ItemStack[] arr) {
        int n = 0;
        if (arr != null) {
            for (ItemStack item : arr) {
                if (item != null && !item.getType().isAir()) {
                    n++;
                }
            }
        }
        return n;
    }

    /* ------------------------------------------------------------------
     * JSON writing - hand-rolled because org.json has no declared Maven
     * dependency in pom.xml (only a shade relocation rule).
     * ------------------------------------------------------------------ */

    static String toJson(SnapshotCodec.Decoded d, UUID uuid, String fallbackName, int payloadBytes) {
        StringBuilder sb = new StringBuilder(16_384);
        sb.append("{\n");
        sb.append("  \"format\": \"astralissync-export\",\n");
        sb.append("  \"formatVersion\": 1,\n");
        sb.append("  \"uuid\": \"").append(esc(uuid.toString())).append("\",\n");
        sb.append("  \"name\": \"").append(esc(fallbackName != null ? fallbackName : "")).append("\",\n");
        sb.append("  \"exportedAt\": ").append(System.currentTimeMillis()).append(",\n");
        sb.append("  \"payloadBytes\": ").append(payloadBytes).append(",\n");
        sb.append("  \"enderChestRows\": ").append(Math.max(1, d.enderChestRows())).append(",\n");

        // inventory
        sb.append("  \"inventory\": {\n");
        writeSlotArray(sb, "storage", d.storage(), 36);
        sb.append(",\n");
        writeSlotArray(sb, "armor", d.armor(), 4);
        sb.append(",\n");
        sb.append("    \"offHand\": ").append(itemJson(d.offHand())).append(",\n");
        sb.append("    \"heldSlot\": ").append(clamp(d.heldSlot(), 0, 8)).append('\n');
        sb.append("  },\n");

        // ender chest
        sb.append("  \"enderChest\": ");
        writeSlotValues(sb, d.enderChest());
        sb.append(",\n");

        // xp
        sb.append("  \"xp\": {\n");
        sb.append("    \"total\": ").append(d.totalXp()).append(",\n");
        sb.append("    \"level\": ").append(d.level()).append(",\n");
        sb.append("    \"progress\": ").append(doubleJson(d.xpProgress())).append('\n');
        sb.append("  },\n");

        // health
        sb.append("  \"health\": {\n");
        sb.append("    \"max\": ").append(doubleJson(d.baseMaxHealth())).append(",\n");
        sb.append("    \"current\": ").append(doubleJson(d.health())).append('\n');
        sb.append("  },\n");

        // hunger
        sb.append("  \"hunger\": {\n");
        sb.append("    \"food\": ").append(d.food()).append(",\n");
        sb.append("    \"saturation\": ").append(doubleJson(d.saturation())).append(",\n");
        sb.append("    \"burning\": ").append(d.burning()).append('\n');
        sb.append("  },\n");

        // location
        sb.append("  \"location\": {\n");
        sb.append("    \"world\": \"").append(esc(d.worldName())).append("\",\n");
        sb.append("    \"x\": ").append(doubleJson(d.x())).append(",\n");
        sb.append("    \"y\": ").append(doubleJson(d.y())).append(",\n");
        sb.append("    \"z\": ").append(doubleJson(d.z())).append(",\n");
        sb.append("    \"yaw\": ").append(doubleJson(d.yaw())).append(",\n");
        sb.append("    \"pitch\": ").append(doubleJson(d.pitch())).append('\n');
        sb.append("  },\n");

        // effects
        sb.append("  \"effects\": [");
        List<org.bukkit.potion.PotionEffect> fx = d.effects();
        for (int i = 0; i < fx.size(); i++) {
            org.bukkit.potion.PotionEffect e = fx.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("{\"type\":\"").append(esc(e.getType().getKey().toString()))
              .append("\",\"amplifier\":").append(e.getAmplifier())
              .append(",\"duration\":").append(e.getDuration())
              .append(",\"ambient\":").append(e.isAmbient())
              .append(",\"particles\":").append(e.hasParticles())
              .append(",\"icon\":").append(e.hasIcon()).append('}');
        }
        sb.append("],\n");

        // misc
        sb.append("  \"gamemode\": \"").append(
                d.gameMode() != null ? esc(d.gameMode().name()) : "SURVIVAL").append("\",\n");
        sb.append("  \"flying\": ").append(d.flying()).append(",\n");
        sb.append("  \"gliding\": ").append(d.gliding()).append('\n');
        sb.append("}\n");
        return sb.toString();
    }

    private static void writeSlotArray(StringBuilder sb, String key, ItemStack[] items, int expectedLen) {
        ItemStack[] safe = items == null ? new ItemStack[expectedLen] : items;
        sb.append("    \"").append(key).append("\": ");
        writeSlotValues(sb, safe);
    }

    private static void writeSlotValues(StringBuilder sb, ItemStack[] items) {
        sb.append('[');
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            appendItemValue(sb, items[i]);
        }
        sb.append(']');
    }

    private static void appendItemValue(StringBuilder sb, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            sb.append("null");
            return;
        }
        try {
            byte[] bytes = item.serializeAsBytes();
            sb.append('"').append(Base64.getEncoder().encodeToString(bytes)).append('"');
        } catch (Exception e) {
            // A single unserializable item must not kill the whole export.
            sb.append("null");
        }
    }

    private static String itemJson(ItemStack item) {
        StringBuilder sb = new StringBuilder();
        appendItemValue(sb, item);
        return sb.toString();
    }

    private static double doubleJson(double v) {
        if (Double.isNaN(v)) {
            return 0.0D;
        }
        if (v == Double.POSITIVE_INFINITY) {
            return Double.MAX_VALUE;
        }
        if (v == Double.NEGATIVE_INFINITY) {
            return -Double.MAX_VALUE;
        }
        return v;
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /* ==================================================================
     * Import
     * ================================================================== */

    /**
     * Reads + parses the JSON async, rebuilds the v3 binary payload
     * (item deserialization on the main thread), then saves it as the given
     * online target's data and invalidates the Redis cache.
     */
    public void importData(org.bukkit.command.CommandSender reporter, Player onlineTarget, Path inFile) {
        new BukkitRunnable() {
            @Override
            public void run() {
                final String raw;
                try {
                    if (!Files.isRegularFile(inFile)) {
                        report(reporter, "§cDatei nicht gefunden: " + inFile.getFileName());
                        return;
                    }
                    long size = Files.size(inFile);
                    if (size > MAX_FILE_BYTES) {
                        report(reporter, "§cDatei zu groß (" + size + " Bytes, max "
                                + MAX_FILE_BYTES + ").");
                        return;
                    }
                    raw = Files.readString(inFile, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    report(reporter, "§cDatei konnte nicht gelesen werden: " + e.getMessage());
                    return;
                }

                final ParsedExport parsed;
                try {
                    parsed = parseExportJson(raw);
                } catch (ImportFormatException e) {
                    report(reporter, "§cUngültige Exportdatei: " + e.getMessage());
                    return;
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Import parse failed", e);
                    report(reporter, "§cDatei konnte nicht geparst werden (ungültiges JSON).");
                    return;
                }

                report(reporter, "§7Datei gültig (" + parsed.itemCount() + " Items), rekonstruiere...");

                // ItemStack.deserializeBytes builds CraftItemStacks -> main thread.
                Bukkit.getScheduler().runTask(plugin, () -> {
                    SnapshotCodec.Decoded decoded;
                    try {
                        decoded = toDecoded(parsed);
                    } catch (ImportFormatException e) {
                        report(reporter, "§cDaten abgelehnt: " + e.getMessage());
                        return;
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.SEVERE, "Import reconstruction failed", e);
                        report(reporter, "§cItems konnten nicht dekodiert werden "
                                + "(kompatible Minecraft-Versionen?)");
                        return;
                    }

                    final byte[] payload = BinaryComposer.write(decoded);

                    // Self-check: our own composer output must be readable by SnapshotCodec.
                    if (SnapshotCodec.decode(payload.clone()) == null) {
                        report(reporter, "§cInterner Fehler: rekonstruierte Daten sind nicht lesbar.");
                        return;
                    }

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            try {
                                String serverId = plugin.getConfig().getString("server-id", "unknown");
                                boolean ok = plugin.getDatabaseManager().saveData(
                                        onlineTarget.getUniqueId(),
                                        onlineTarget.getName(),
                                        payload,
                                        serverId);
                                if (!ok) {
                                    report(reporter, "§cSpeichern in der Datenbank fehlgeschlagen.");
                                    return;
                                }
                                // Invalidate stale cache only when Redis is reachable;
                                // invalidateCache swallows its own IO errors anyway.
                                if (plugin.getRedisManager() != null
                                        && plugin.getRedisManager().isConnected()) {
                                    plugin.getRedisManager().invalidateCache(onlineTarget.getUniqueId());
                                }
                                report(reporter, "§aImport erfolgreich! Daten für §e"
                                        + onlineTarget.getName()
                                        + "§a gespeichert (UUID " + onlineTarget.getUniqueId() + ").");
                                report(reporter, "§7Beim nächsten Join des Spielers werden die Daten "
                                        + "geladen (oder via /astralissync save nach Restore prüfen).");
                            } catch (Exception e) {
                                plugin.getLogger().log(Level.SEVERE, "Import persist failed", e);
                                report(reporter, "§cImport fehlgeschlagen: " + e.getMessage());
                            }
                        }
                    }.runTaskAsynchronously(plugin);
                });
            }
        }.runTaskAsynchronously(plugin);
    }

    /* ------------------------------------------------------------------
     * Decoded construction from parsed JSON (strict bounds validation)
     * ------------------------------------------------------------------ */

    private SnapshotCodec.Decoded toDecoded(ParsedExport p) throws IOException, ImportFormatException {
        ItemStack[] storage = decodeSlots(p.inventoryStorage, STORAGE_SIZE);
        ItemStack[] armor = decodeSlots(p.inventoryArmor, ARMOR_SIZE);
        ItemStack offHand = decodeOne(p.offHand);
        ItemStack[] enderChest = decodeSlots(p.enderChest, p.enderChestRows * 9);

        List<org.bukkit.potion.PotionEffect> effects = new ArrayList<>(p.effects.size());
        for (ParsedEffect pe : p.effects) {
            org.bukkit.potion.PotionEffectType type =
                    org.bukkit.potion.PotionEffectType.getByKey(
                            org.bukkit.NamespacedKey.minecraft(pe.type()));
            if (type == null) {
                throw new ImportFormatException("Unbekannter Effekt: " + pe.type());
            }
            if (pe.duration() <= 0 || pe.amplifier() < 0 || pe.amplifier() > 255) {
                throw new ImportFormatException(
                        "Effekt-Werte außerhalb des gültigen Bereichs: " + pe.type());
            }
            effects.add(new org.bukkit.potion.PotionEffect(type,
                    Math.max(pe.duration(), 1), pe.amplifier(),
                    pe.ambient(), pe.particles(), pe.icon()));
        }

        org.bukkit.GameMode gameMode;
        try {
            gameMode = org.bukkit.GameMode.valueOf(p.gameMode());
        } catch (IllegalArgumentException e) {
            throw new ImportFormatException("Unbekannter Gamemode: " + p.gameMode());
        }

        validateRange(p.healthMax, 0.5D, 4096.0D, "health.max");
        validateRange(p.healthCurrent, 0.0D, p.healthMax, "health.current");
        validateRange(p.food, 0, 20, "hunger.food");
        validateRange(p.saturation, 0.0D, 1024.0D, "hunger.saturation");
        validateRange(p.xpTotal, 0, Integer.MAX_VALUE, "xp.total");
        validateRange(p.level, 0, Integer.MAX_VALUE, "xp.level");
        validateProgress(p.xpProgress);

        return new SnapshotCodec.Decoded(
                SnapshotCodec.CURRENT_VERSION,
                storage, armor, offHand,
                p.enderChestRows(), enderChest,
                clamp(p.heldSlot(), 0, 8),
                p.healthMax(), p.healthCurrent(),
                p.food(), (float) p.saturation(), p.burning(),
                p.xpTotal(), p.level(), (float) p.xpProgress(),
                effects,
                p.worldName(), p.x(), p.y(), p.z(),
                (float) p.yaw(), (float) p.pitch(),
                gameMode, p.flying(), p.gliding());
    }

    /** Deserializes exactly {@code length} entries; absent entries become null slots. */
    private static ItemStack[] decodeSlots(List<String> base64List, int length)
            throws IOException, ImportFormatException {
        if (base64List.size() != length) {
            throw new ImportFormatException("Slot-Anzahl falsch: erwartet " + length
                    + ", gefunden " + base64List.size());
        }
        ItemStack[] result = new ItemStack[length];
        for (int i = 0; i < length; i++) {
            result[i] = decodeOne(base64List.get(i));
        }
        return result;
    }

    private static ItemStack decodeOne(String b64) throws IOException, ImportFormatException {
        if (b64 == null) {
            return null;
        }
        if (b64.isEmpty()) {
            throw new ImportFormatException("Leerer Base64-String in Item-Daten");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new ImportFormatException("Ungültiges Base64 in Item-Daten");
        }
        if (bytes.length > MAX_ITEM_BYTES) {
            throw new ImportFormatException("Item-Payload zu groß: " + bytes.length);
        }
        return ItemStack.deserializeBytes(bytes);
    }

    private static void validateRange(long v, long min, long max, String field)
            throws ImportFormatException {
        if (v < min || v > max) {
            throw new ImportFormatException(field + " außerhalb des gültigen Bereichs: " + v);
        }
    }

    private static void validateRange(double v, double min, double max, String field)
            throws ImportFormatException {
        if (!isFinite(v) || v < min || v > max) {
            throw new ImportFormatException(field + " außerhalb des gültigen Bereichs: " + v);
        }
    }

    private static void validateProgress(double v) throws ImportFormatException {
        if (!isFinite(v) || v < 0.0D || v >= 1.0D) {
            throw new ImportFormatException("xp.progress muss in [0;1) liegen: " + v);
        }
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, max));
    }

    /* ==================================================================
     * Lenient but strict-schema JSON parsing (this format only).
     * Accepts any whitespace/key ordering; requires every documented field
     * with correct primitive type; rejects unknown keys and trailing junk.
     * ================================================================== */

    private record ParsedExport(UUID uuid, String name, long exportedAt, long payloadBytes,
                                int enderChestRows,
                                List<String> inventoryStorage, List<String> inventoryArmor,
                                String offHand, int heldSlot,
                                List<String> enderChest,
                                int xpTotal, int level, double xpProgress,
                                double healthMax, double healthCurrent,
                                int food, double saturation, boolean burning,
                                String worldName, double x, double y, double z,
                                double yaw, double pitch,
                                String gameMode, boolean flying, boolean gliding,
                                List<ParsedEffect> effects) {

        int itemCount() {
            int n = 0;
            n += countNonNull(inventoryStorage);
            n += countNonNull(inventoryArmor);
            if (offHand != null) {
                n++;
            }
            n += countNonNull(enderChest);
            return n;
        }

        private static int countNonNull(List<String> l) {
            int n = 0;
            for (String s : l) {
                if (s != null) {
                    n++;
                }
            }
            return n;
        }
    }

    private record ParsedEffect(String type, int amplifier, int duration,
                                boolean ambient, boolean particles, boolean icon) {
    }

    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;

    private static final class ImportFormatException extends Exception {
        ImportFormatException(String message) {
            super(message);
        }
    }

    private ParsedExport parseExportJson(String raw) throws ImportFormatException, IOException {
        JsonReader r = new JsonReader(raw);
        r.skipWs();
        r.expect('{');

        UUID uuid = null;
        String name = null;
        Long exportedAt = null;
        Long payloadBytes = null;
        Integer ecRows = null;
        List<String> storage = null;
        List<String> armor = null;
        String offHand = null;
        Integer heldSlot = null;
        List<String> enderChest = null;
        Integer xpTotal = null;
        Integer level = null;
        Double progress = null;
        Double healthMax = null;
        Double healthCurrent = null;
        Integer food = null;
        Double saturation = null;
        Boolean burning = null;
        String worldName = null;
        Double x = null;
        Double y = null;
        Double z = null;
        Double yaw = null;
        Double pitch = null;
        String gameMode = null;
        Boolean flying = null;
        Boolean gliding = null;
        List<ParsedEffect> effects = null;

        boolean first = true;
        while (true) {
            r.skipWs();
            if (r.peek() == '}') {
                break;
            }
            if (!first) {
                r.expect(',');
            }
            first = false;
            r.skipWs();
            String key = r.readString();
            r.skipWs();
            r.expect(':');
            r.skipWs();
            switch (key) {
                case "format" -> {
                    String f = r.readString();
                    if (!"astralissync-export".equals(f)) {
                        throw new ImportFormatException(
                                "format muss 'astralissync-export' sein, war: " + f);
                    }
                }
                case "formatVersion" -> {
                    long v = r.readLong();
                    if (v != 1) {
                        throw new ImportFormatException("Nicht unterstütztes formatVersion: " + v);
                    }
                }
                case "uuid" -> {
                    try {
                        uuid = UUID.fromString(r.readString());
                    } catch (IllegalArgumentException e) {
                        throw new ImportFormatException("uuid ist keine gültige UUID");
                    }
                }
                case "name" -> name = r.readString();
                case "exportedAt" -> exportedAt = r.readLong();
                case "payloadBytes" -> payloadBytes = r.readLong();
                case "enderChestRows" -> {
                    long rows = r.readLong();
                    if (rows < 1 || rows > MAX_ENDER_CHEST_ROWS) {
                        throw new ImportFormatException(
                                "enderChestRows außerhalb 1.." + MAX_ENDER_CHEST_ROWS);
                    }
                    ecRows = (int) rows;
                }
                case "inventory" -> {
                    r.expect('{');
                    boolean firstInv = true;
                    while (true) {
                        r.skipWs();
                        if (r.peek() == '}') {
                            break;
                        }
                        if (!firstInv) {
                            r.expect(',');
                        }
                        firstInv = false;
                        r.skipWs();
                        String ik = r.readString();
                        r.skipWs();
                        r.expect(':');
                        r.skipWs();
                        switch (ik) {
                            case "storage" -> storage = readStringOrNullArray(r);
                            case "armor" -> armor = readStringOrNullArray(r);
                            case "offHand" -> offHand = r.readStringOrNull();
                            case "heldSlot" -> {
                                long hs = r.readLong();
                                if (hs < 0 || hs > 8) {
                                    throw new ImportFormatException("heldSlot außerhalb 0..8");
                                }
                                heldSlot = (int) hs;
                            }
                            default -> throw new ImportFormatException("Unbekanntes Feld inventory." + ik);
                        }
                        r.skipWs();
                    }
                    r.expect('}');
                }
                case "enderChest" -> enderChest = readStringOrNullArray(r);
                case "xp" -> {
                    double[] vals = readTriple(r, "xp", "total", "level", "progress", true);
                    xpTotal = (int) vals[0];
                    level = (int) vals[1];
                    progress = vals[2];
                }
                case "health" -> {
                    double[] vals = readPair(r, "health", "max", "current");
                    healthMax = vals[0];
                    healthCurrent = vals[1];
                }
                case "hunger" -> {
                    r.expect('{');
                    boolean firstHu = true;
                    while (true) {
                        r.skipWs();
                        if (r.peek() == '}') {
                            break;
                        }
                        if (!firstHu) {
                            r.expect(',');
                        }
                        firstHu = false;
                        r.skipWs();
                        String huk = r.readString();
                        r.skipWs();
                        r.expect(':');
                        r.skipWs();
                        switch (huk) {
                            case "food" -> food = (int) r.readLong();
                            case "saturation" -> saturation = r.readNumber();
                            case "burning" -> burning = r.readBool();
                            default -> throw new ImportFormatException("Unbekanntes Feld hunger." + huk);
                        }
                        r.skipWs();
                    }
                    r.expect('}');
                }
                case "location" -> {
                    r.expect('{');
                    boolean firstL = true;
                    while (true) {
                        r.skipWs();
                        if (r.peek() == '}') {
                            break;
                        }
                        if (!firstL) {
                            r.expect(',');
                        }
                        firstL = false;
                        r.skipWs();
                        String lk = r.readString();
                        r.skipWs();
                        r.expect(':');
                        r.skipWs();
                        switch (lk) {
                            case "world" -> worldName = r.readString();
                            case "x" -> x = r.readNumber();
                            case "y" -> y = r.readNumber();
                            case "z" -> z = r.readNumber();
                            case "yaw" -> yaw = r.readNumber();
                            case "pitch" -> pitch = r.readNumber();
                            default -> throw new ImportFormatException("Unbekanntes Feld location." + lk);
                        }
                        r.skipWs();
                    }
                    r.expect('}');
                }
                case "effects" -> effects = readEffects(r);
                case "gamemode" -> gameMode = r.readString();
                case "flying" -> flying = r.readBool();
                case "gliding" -> gliding = r.readBool();
                default -> throw new ImportFormatException("Unbekanntes Feld: '" + key + "'");
            }
            r.skipWs();
        }
        r.expect('}');
        requireEndOfInput(r);

        // ---- completeness ----
        require(uuid != null, "uuid fehlt");
        require(name != null, "name fehlt");
        require(exportedAt != null, "exportedAt fehlt");
        require(ecRows != null, "enderChestRows fehlt");
        require(storage != null, "inventory.storage fehlt");
        require(armor != null, "inventory.armor fehlt");
        require(heldSlot != null, "inventory.heldSlot fehlt");
        require(enderChest != null, "enderChest fehlt");
        require(xpTotal != null && level != null && progress != null, "xp.* unvollständig");
        require(healthMax != null && healthCurrent != null, "health.* unvollständig");
        require(food != null && saturation != null && burning != null, "hunger.* unvollständig");
        require(worldName != null && x != null && y != null && z != null
                && yaw != null && pitch != null, "location.* unvollständig");
        require(effects != null, "effects fehlt");
        require(gameMode != null, "gamemode fehlt");
        require(flying != null && gliding != null, "flying/gliding fehlt");

        // ---- shape / bounds ----
        require(storage.size() == STORAGE_SIZE,
                "inventory.storage braucht genau " + STORAGE_SIZE + " Einträge");
        require(armor.size() == ARMOR_SIZE,
                "inventory.armor braucht genau " + ARMOR_SIZE + " Einträge");
        require(enderChest.size() == ecRows * 9,
                "enderChest braucht " + (ecRows * 9) + " Einträge bei " + ecRows + " Reihen");
        require(worldName.length() <= 128, "location.world zu lang");
        require(!worldName.isBlank(), "location.world leer");
        require(name.length() <= 16, "name länger als 16 Zeichen");
        require(isFinite(x) && isFinite(y) && isFinite(z)
                && Math.abs(y) <= 10_000_000.0D, "Koordinaten außerhalb des plausiblen Bereichs");
        require(isFinite(yaw) && Math.abs(yaw) <= 360.0F * 100.0F, "yaw unplausibel");
        require(isFinite(pitch) && Math.abs(pitch) <= 360.0F * 100.0F, "pitch unplausibel");
        if (payloadBytes != null) {
            require(payloadBytes >= 0 && payloadBytes <= MAX_FILE_BYTES, "payloadBytes unplausibel");
        }

        return new ParsedExport(uuid, name, exportedAt,
                payloadBytes != null ? payloadBytes : -1L,
                ecRows, storage, armor, offHand, heldSlot, enderChest,
                xpTotal, level, progress, healthMax, healthCurrent, food, saturation, burning,
                worldName, x, y, z, yaw, pitch, gameMode, flying, gliding, effects);
    }

    /** Object with exactly two numeric members (any order). */
    private static double[] readPair(JsonReader r, String ctx, String k0, String k1)
            throws ImportFormatException, IOException {
        double[] out = new double[2];
        boolean[] seen = new boolean[2];
        r.expect('{');
        boolean first = true;
        while (true) {
            r.skipWs();
            if (r.peek() == '}') {
                break;
            }
            if (!first) {
                r.expect(',');
            }
            first = false;
            r.skipWs();
            String key = r.readString();
            r.skipWs();
            r.expect(':');
            r.skipWs();
            if (key.equals(k0)) {
                out[0] = r.readNumber();
                seen[0] = true;
            } else if (key.equals(k1)) {
                out[1] = r.readNumber();
                seen[1] = true;
            } else {
                throw new ImportFormatException("Unbekanntes Feld " + ctx + "." + key);
            }
            r.skipWs();
        }
        r.expect('}');
        require(seen[0], ctx + "." + k0 + " fehlt");
        require(seen[1], ctx + "." + k1 + " fehlt");
        return out;
    }

    /** Object with two integer members and one numeric member (any order). */
    private static double[] readTriple(JsonReader r, String ctx, String i0, String i1, String d2,
                                       boolean lastIsFractional)
            throws ImportFormatException, IOException {
        double[] out = new double[3];
        boolean[] seen = new boolean[3];
        r.expect('{');
        boolean first = true;
        while (true) {
            r.skipWs();
            if (r.peek() == '}') {
                break;
            }
            if (!first) {
                r.expect(',');
            }
            first = false;
            r.skipWs();
            String key = r.readString();
            r.skipWs();
            r.expect(':');
            r.skipWs();
            if (key.equals(i0)) {
                out[0] = r.readLong();
                seen[0] = true;
            } else if (key.equals(i1)) {
                out[1] = r.readLong();
                seen[1] = true;
            } else if (key.equals(d2)) {
                if (!lastIsFractional) {
                    out[2] = r.readLong();
                } else {
                    out[2] = r.readNumber();
                }
                seen[2] = true;
            } else {
                throw new ImportFormatException("Unbekanntes Feld " + ctx + "." + key);
            }
            r.skipWs();
        }
        r.expect('}');
        require(seen[0], ctx + "." + i0 + " fehlt");
        require(seen[1], ctx + "." + i1 + " fehlt");
        require(seen[2], ctx + "." + d2 + " fehlt");
        return out;
    }

    private static void requireEndOfInput(JsonReader r) throws ImportFormatException {
        r.skipWs();
        if (r.hasMore()) {
            throw new ImportFormatException("Unerwarteter Inhalt nach dem JSON-Objekt");
        }
    }

    private static List<String> readStringOrNullArray(JsonReader r)
            throws ImportFormatException, IOException {
        r.skipWs();
        r.expect('[');
        List<String> out = new ArrayList<>();
        r.skipWs();
        if (r.peek() == ']') {
            r.advance();
            return out;
        }
        while (true) {
            out.add(r.readStringOrNull());
            r.skipWs();
            if (r.peek() == ',') {
                r.advance();
                continue;
            }
            break;
        }
        r.expect(']');
        return out;
    }

    private static List<ParsedEffect> readEffects(JsonReader r)
            throws ImportFormatException, IOException {
        r.skipWs();
        r.expect('[');
        List<ParsedEffect> out = new ArrayList<>();
        r.skipWs();
        if (r.peek() == ']') {
            r.advance();
            return out;
        }
        while (true) {
            r.skipWs();
            r.expect('{');
            String type = null;
            Long amplifier = null;
            Long duration = null;
            Boolean ambient = null;
            Boolean particles = null;
            Boolean icon = null;
            boolean firstE = true;
            while (true) {
                r.skipWs();
                if (r.peek() == '}') {
                    break;
                }
                if (!firstE) {
                    r.expect(',');
                }
                firstE = false;
                r.skipWs();
                String k = r.readString();
                r.skipWs();
                r.expect(':');
                r.skipWs();
                switch (k) {
                    case "type" -> type = r.readString();
                    case "amplifier" -> amplifier = r.readLong();
                    case "duration" -> duration = r.readLong();
                    case "ambient" -> ambient = r.readBool();
                    case "particles" -> particles = r.readBool();
                    case "icon" -> icon = r.readBool();
                    default -> throw new ImportFormatException("Unbekanntes Effekt-Feld: " + k);
                }
                r.skipWs();
            }
            r.expect('}');
            require(type != null && amplifier != null && duration != null
                    && ambient != null && particles != null && icon != null,
                    "Effekt-Eintrag unvollständig");
            require(type.length() <= 128, "Effekt-Typ zu lang");
            require(amplifier >= 0 && amplifier <= 255, "amplifier außerhalb 0..255");
            require(duration >= 1, "duration muss positiv sein");
            out.add(new ParsedEffect(type, amplifier.intValue(), duration.intValue(),
                    ambient, particles, icon));
            r.skipWs();
            if (r.peek() == ',') {
                r.advance();
                continue;
            }
            break;
        }
        r.expect(']');
        return out;
    }

    private static void require(boolean cond, String message) throws ImportFormatException {
        if (!cond) {
            throw new ImportFormatException(message);
        }
    }

    /* ==================================================================
     * Minimal strict JSON tokenizer/parser (RFC 8259 subset):
     * objects, arrays, strings with escapes (backslash-uXXXX included),
     * integers and doubles, true/false/null. No duplicate-key tolerance
     * beyond last-wins; rejects NaN/Infinity literals and trailing content.
     * ================================================================== */

    private static final class JsonReader {
        private final String s;
        private int i;

        JsonReader(String s) {
            this.s = s;
        }

        boolean hasMore() {
            return i < s.length();
        }

        char peek() throws ImportFormatException {
            if (!hasMore()) {
                throw new ImportFormatException("Unerwartetes Ende der Datei");
            }
            return s.charAt(i);
        }

        void advance() {
            i++;
        }

        void skipWs() {
            while (hasMore()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
        }

        void expect(char c) throws ImportFormatException {
            if (peek() != c) {
                throw new ImportFormatException("Erwartete '" + c + "' an Position " + i);
            }
            advance();
        }

        long readLong() throws ImportFormatException {
            skipWs();
            int start = i;
            if (peek() == '-') {
                advance();
            }
            while (hasMore() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                advance();
            }
            if (i == start || (i == start + 1 && s.charAt(start) == '-')) {
                throw new ImportFormatException("Ganzzahl erwartet an Position " + start);
            }
            try {
                return Long.parseLong(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw new ImportFormatException("Zahl außerhalb des Bereichs an Position " + start);
            }
        }

        /** Accepts integers and doubles (incl. exponents); returns them as double. */
        double readNumber() throws ImportFormatException {
            skipWs();
            int start = i;
            if (peek() == '-') {
                advance();
            }
            boolean digits = false;
            while (hasMore() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                advance();
                digits = true;
            }
            if (hasMore() && s.charAt(i) == '.') {
                advance();
                while (hasMore() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                    advance();
                    digits = true;
                }
            }
            if (hasMore() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                advance();
                if (hasMore() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                    advance();
                }
                while (hasMore() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                    advance();
                }
            }
            if (!digits) {
                throw new ImportFormatException("Zahl erwartet an Position " + start);
            }
            try {
                return Double.parseDouble(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw new ImportFormatException("Ungültige Zahl an Position " + start);
            }
        }

        boolean readBool() throws ImportFormatException {
            skipWs();
            if (s.startsWith("true", i)) {
                i += 4;
                return true;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return false;
            }
            throw new ImportFormatException("true/false erwartet an Position " + i);
        }

        /** Reads a string; throws when the value is null. */
        String readString() throws ImportFormatException {
            skipWs();
            if (s.startsWith("null", i)) {
                throw new ImportFormatException("String erwartet (null gefunden) an Position " + i);
            }
            String value = readStringOrNull();
            if (value == null) {
                throw new ImportFormatException("String erwartet an Position " + i);
            }
            return value;
        }

        /** Reads a string or the literal null (returns null then). */
        String readStringOrNull() throws ImportFormatException {
            skipWs();
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            if (peek() != '"') {
                throw new ImportFormatException("String erwartet an Position " + i);
            }
            advance();
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (!hasMore()) {
                    throw new ImportFormatException("Nicht terminierter String");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (!hasMore()) {
                        throw new ImportFormatException("Ungültige Escape-Sequenz");
                    }
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) {
                                throw new ImportFormatException("Ungültige \\u-Escape-Sequenz");
                            }
                            String hex = s.substring(i, i + 4);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException nfe) {
                                throw new ImportFormatException(
                                        "Ungültige \\u-Escape-Sequenz: " + hex);
                            }
                            i += 4;
                        }
                        default -> throw new ImportFormatException(
                                "Unbekannte Escape-Sequenz: \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }
    }

    /* ==================================================================
     * BinaryComposer: writes the EXACT v3 layout of SnapshotCodec.write()
     * from a Decoded snapshot.
     * Layout mirror-checked line by line against SnapshotCodec:
     *   [int version=3][flags int 0b111111]
     *   storage array | armor array | offhand item
     *   [int ecRows][int ecSlots] + ec items
     *   [int heldSlot][double baseHealth][double health]
     *   [int food][float saturation][int burning]
     *   [int xpTotal][int level][float xp]
     *   [int effectCount](+entries: utf,int,int,bool,bool,bool)
     *   [utf world][double x][double y][double z][float yaw][float pitch]
     *   [utf gamemode][bool flying][bool gliding]
     *   item encoding: bool present + [int len + bytes]; air = absent
     * ================================================================== */

    static final class BinaryComposer {

        private BinaryComposer() {
        }

        static byte[] write(SnapshotCodec.Decoded d) {
            try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192);
                 DataOutputStream out = new DataOutputStream(bytes)) {

                out.writeInt(SnapshotCodec.CURRENT_VERSION);
                out.writeInt(flags());

                // Inventory: storage (36), armor (4), off hand
                writeItemArray(out, d.storage());
                writeItemArray(out, d.armor());
                writeItem(out, d.offHand());

                // Ender chest rows + slot count + items
                int slots = clamp(d.enderChest().length, 0, Math.max(1, d.enderChestRows()) * 9);
                out.writeInt(Math.max(1, d.enderChestRows()));
                out.writeInt(slots);
                for (int i = 0; i < slots; i++) {
                    writeItem(out, d.enderChest()[i]);
                }

                out.writeInt(clamp(d.heldSlot(), 0, 8));

                out.writeDouble(d.baseMaxHealth());
                out.writeDouble(d.health());

                out.writeInt(d.food());
                out.writeFloat(d.saturation());
                out.writeInt(d.burning() ? 1 : 0);

                out.writeInt(d.totalXp());
                out.writeInt(d.level());
                out.writeFloat(d.xpProgress());

                List<org.bukkit.potion.PotionEffect> fx =
                        d.effects() == null ? List.of() : d.effects();
                out.writeInt(fx.size());
                for (org.bukkit.potion.PotionEffect effect : fx) {
                    writeUTF(out, effect.getType().getKey().toString());
                    out.writeInt(effect.getAmplifier());
                    out.writeInt(effect.getDuration());
                    out.writeBoolean(effect.isAmbient());
                    out.writeBoolean(effect.hasParticles());
                    out.writeBoolean(effect.hasIcon());
                }

                writeUTF(out, d.worldName());
                out.writeDouble(d.x());
                out.writeDouble(d.y());
                out.writeDouble(d.z());
                out.writeFloat(d.yaw());
                out.writeFloat(d.pitch());

                writeUTF(out, d.gameMode() != null ? d.gameMode().name() : "SURVIVAL");
                out.writeBoolean(d.flying());
                out.writeBoolean(d.gliding());

                out.flush();
                return bytes.toByteArray();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException("Failed to compose snapshot payload", e);
            }
        }

        static int flags() {
            return 0b111111; // HEALTH|HUNGER|XP|EFFECTS|LOCATION|GAMEMODE
        }

        private static void writeItemArray(DataOutputStream out, ItemStack[] items) throws IOException {
            ItemStack[] safe = items == null ? new ItemStack[0] : items;
            out.writeInt(safe.length);
            for (ItemStack item : safe) {
                writeItem(out, item);
            }
        }

        private static void writeItem(DataOutputStream out, ItemStack item) throws IOException {
            if (item == null || item.getType().isAir()) {
                out.writeBoolean(false);
            } else {
                out.writeBoolean(true);
                byte[] serialized = item.serializeAsBytes();
                out.writeInt(serialized.length);
                out.write(serialized);
            }
        }

        private static void writeUTF(DataOutputStream out, String s) throws IOException {
            out.writeUTF(s == null ? "" : s);
        }

        private static int clamp(int v, int min, int max) {
            return Math.max(min, Math.min(v, max));
        }
    }

    /* ==================================================================
     * Reporting helpers
     * ================================================================== */

    private void report(org.bukkit.command.CommandSender reporter, String legacyMessage) {
        reporter.sendMessage(legacyMessage);
    }
}
