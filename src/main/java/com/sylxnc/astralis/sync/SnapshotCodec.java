package com.sylxnc.astralis.sync;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Versioned binary codec for a full player snapshot.
 * <p>
 * Layout: [int version][flags int][payload...]
 * Item stacks are stored via {@link ItemStack#serializeAsBytes()} so the format
 * follows the vanilla NBT serialization and survives cross-server moves as long
 * as both servers run compatible Minecraft data versions.
 * <p>
 * v2: gamemode + flying + gliding. v3: configurable ender chest rows.
 */
public final class SnapshotCodec {

    public static final int CURRENT_VERSION = 3;

    private static final int FLAG_HEALTH = 1 << 0;
    private static final int FLAG_HUNGER = 1 << 1;
    private static final int FLAG_XP = 1 << 2;
    private static final int FLAG_EFFECTS = 1 << 3;
    private static final int FLAG_LOCATION = 1 << 4;
    private static final int FLAG_GAMEMODE = 1 << 5;

    private SnapshotCodec() {
    }

    /* ------------------------------------------------------------------
     * Writing (live player -> bytes)
     * ------------------------------------------------------------------ */

    public static byte[] write(Player player) {
        return write(player, Math.max(1, player.getEnderChest().getSize() / 9));
    }

    public static byte[] write(Player player, int enderChestRows) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192);
             DataOutputStream out = new DataOutputStream(bytes)) {

            out.writeInt(CURRENT_VERSION);
            out.writeInt(FLAG_HEALTH | FLAG_HUNGER | FLAG_XP | FLAG_EFFECTS | FLAG_LOCATION | FLAG_GAMEMODE);

            // Inventory: storage (36), armor (4), off hand
            writeItemArray(out, player.getInventory().getStorageContents());
            writeItemArray(out, player.getInventory().getArmorContents());
            writeItem(out, player.getInventory().getItemInOffHand());

            // Ender chest rows (only the first enderChestRows*9 slots)
            ItemStack[] ec = player.getEnderChest().getContents();
            int slots = Math.min(enderChestRows * 9, ec.length);
            out.writeInt(enderChestRows);
            out.writeInt(slots);
            for (int i = 0; i < slots; i++) {
                writeItem(out, ec[i]);
            }

            out.writeInt(player.getInventory().getHeldItemSlot());

            AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
            out.writeDouble(maxHealth != null ? maxHealth.getBaseValue() : 20.0D);
            out.writeDouble(Math.min(player.getHealth(), maxHealth != null ? maxHealth.getValue() : 20.0D));

            out.writeInt(player.getFoodLevel());
            out.writeFloat(player.getSaturation());
            out.writeInt(player.getFireTicks() > 0 ? 1 : 0);

            out.writeInt(player.getTotalExperience());
            out.writeInt(player.getLevel());
            out.writeFloat(player.getExp());

            Collection<PotionEffect> effects = player.getActivePotionEffects();
            out.writeInt(effects.size());
            for (PotionEffect effect : effects) {
                writeUTF(out, effect.getType().getKey().toString());
                out.writeInt(effect.getAmplifier());
                out.writeInt(effect.getDuration());
                out.writeBoolean(effect.isAmbient());
                out.writeBoolean(effect.hasParticles());
                out.writeBoolean(effect.hasIcon());
            }

            Location loc = player.getLocation();
            writeUTF(out, loc.getWorld() != null ? loc.getWorld().getName() : "world");
            out.writeDouble(loc.getX());
            out.writeDouble(loc.getY());
            out.writeDouble(loc.getZ());
            out.writeFloat(loc.getYaw());
            out.writeFloat(loc.getPitch());

            writeUTF(out, player.getGameMode().name());
            out.writeBoolean(player.isFlying());
            out.writeBoolean(player.isGliding());

            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to snapshot " + player.getName(), e);
        }
    }

    /* ------------------------------------------------------------------
     * Parsing (bytes -> immutable snapshot view)
     * ------------------------------------------------------------------ */

    /** Immutable parsed view of a stored payload. */
    public record Decoded(int version,
                          ItemStack[] storage, ItemStack[] armor, ItemStack offHand,
                          int enderChestRows, ItemStack[] enderChest,
                          int heldSlot,
                          double baseMaxHealth, double health,
                          int food, float saturation, boolean burning,
                          int totalXp, int level, float xpProgress,
                          List<PotionEffect> effects,
                          String worldName, double x, double y, double z, float yaw, float pitch,
                          GameMode gameMode, boolean flying, boolean gliding) {
    }

    /** Parses a payload; null when unknown/corrupt. */
    public static Decoded decode(byte[] payload) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int version = in.readInt();
            if (version > CURRENT_VERSION || version < 1) {
                pluginLog(Level.WARNING, "Unsupported snapshot version " + version + ", skipping.");
                return null;
            }
            in.readInt(); // flags - all known sections are read unconditionally

            ItemStack[] storage = readItemArray(in);
            ItemStack[] armor = readItemArray(in);
            ItemStack offHand = readItem(in);

            int ecRows = 3;
            ItemStack[] enderChest;
            if (version >= 3) {
                ecRows = Math.max(1, in.readInt());
                int slots = in.readInt();
                enderChest = new ItemStack[Math.max(slots, 0)];
                for (int i = 0; i < enderChest.length; i++) {
                    enderChest[i] = readItem(in);
                }
            } else {
                enderChest = readItemArray(in);
            }

            int heldSlot = in.readInt();

            double baseMaxHealth = in.readDouble();
            double health = in.readDouble();

            int food = in.readInt();
            float saturation = in.readFloat();
            boolean burning = in.readInt() == 1;

            int totalXp = in.readInt();
            int level = in.readInt();
            float xpProgress = in.readFloat();

            int effectCount = in.readInt();
            List<PotionEffect> effects = new ArrayList<>(effectCount);
            for (int i = 0; i < effectCount; i++) {
                String key = readUTF(in);
                int amplifier = in.readInt();
                int duration = in.readInt();
                boolean ambient = in.readBoolean();
                boolean particles = in.readBoolean();
                boolean icon = in.readBoolean();
                PotionEffectType type = PotionEffectType.getByKey(NamespacedKey.minecraft(key));
                if (type != null) {
                    effects.add(new PotionEffect(type, Math.max(duration, 1), amplifier, ambient, particles, icon));
                }
            }

            String worldName = readUTF(in);
            double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
            float yaw = in.readFloat(), pitch = in.readFloat();

            GameMode gameMode = parseGameMode(readUTF(in));
            boolean flying = in.readBoolean();
            boolean gliding = in.readBoolean();

            return new Decoded(version, storage, armor, offHand, ecRows, enderChest, heldSlot,
                    baseMaxHealth, health, food, saturation, burning,
                    totalXp, level, xpProgress, effects,
                    worldName, x, y, z, yaw, pitch, gameMode, flying, gliding);
        } catch (Exception e) {
            pluginLog(Level.SEVERE, "Corrupt player snapshot dropped: " + e.getMessage());
            return null;
        }
    }

    /* ------------------------------------------------------------------
     * Applying
     * ------------------------------------------------------------------ */

    /** Returns a main-thread applier for the payload, or null if unreadable. */
    public static Consumer<Player> readApplier(byte[] payload) {
        Decoded d = decode(payload);
        if (d == null) {
            return null;
        }
        return player -> apply(player, d);
    }

    /** Applies a decoded snapshot on the main thread (server-side call only). */
    @SuppressWarnings("deprecation")
    public static void apply(Player p, Decoded d) {
        p.getInventory().setStorageContents(clone(d.storage()));
        p.getInventory().setArmorContents(clone(d.armor()));
        p.getInventory().setItemInOffHand(d.offHand() == null ? null : d.offHand().clone());

        org.bukkit.inventory.Inventory ec = p.getEnderChest();
        ec.clear();
        ItemStack[] ecData = d.enderChest();
        int copyable = Math.min(ec.getSize(), ecData.length);
        for (int i = 0; i < copyable; i++) {
            if (ecData[i] != null) {
                ec.setItem(i, ecData[i].clone());
            }
        }

        p.getInventory().setHeldItemSlot(Math.min(Math.max(d.heldSlot(), 0), 8));

        AttributeInstance maxHealthAttr = p.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr != null && d.baseMaxHealth() > 0) {
            maxHealthAttr.setBaseValue(d.baseMaxHealth());
        }
        if (d.health() > 0 && !p.isDead()) {
            p.setHealth(Math.min(d.health(), maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0D));
        }
        p.setFoodLevel(Math.max(d.food(), 0));
        p.setSaturation(d.saturation());
        p.setTotalExperience(d.totalXp());
        p.setLevel(d.level());
        p.setExp(d.xpProgress());

        for (PotionEffect active : p.getActivePotionEffects()) {
            p.removePotionEffect(active.getType());
        }
        for (PotionEffect effect : d.effects()) {
            p.addPotionEffect(effect);
        }

        if (d.gameMode() != null) {
            p.setGameMode(d.gameMode());
        }
        World world = Bukkit.getWorld(d.worldName());
        if (world != null) {
            p.teleport(new Location(world, d.x(), d.y(), d.z(), d.yaw(), d.pitch()));
        }
        p.setFlying(d.flying() && p.getAllowFlight());
        p.setGliding(d.gliding());
        if (!d.burning()) {
            p.setFireTicks(0);
        }
    }

    private static GameMode parseGameMode(String name) {
        try {
            return GameMode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /* ------------------------------------------------------------------
     * Item helpers
     * ------------------------------------------------------------------ */

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

    private static ItemStack[] readItemArray(DataInputStream in) throws IOException {
        int len = in.readInt();
        ItemStack[] result = new ItemStack[Math.max(len, 0)];
        for (int i = 0; i < len; i++) {
            result[i] = readItem(in);
        }
        return result;
    }

    private static ItemStack readItem(DataInputStream in) throws IOException {
        if (!in.readBoolean()) {
            return null;
        }
        int len = in.readInt();
        byte[] data = new byte[len];
        in.readFully(data);
        return ItemStack.deserializeBytes(data);
    }

    private static ItemStack[] clone(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            copy[i] = items[i] == null ? null : items[i].clone();
        }
        return copy;
    }

    private static void writeUTF(DataOutputStream out, String s) throws IOException {
        out.writeUTF(s == null ? "" : s);
    }

    private static String readUTF(DataInputStream in) throws IOException {
        return in.readUTF();
    }

    private static void pluginLog(Level level, String message) {
        Bukkit.getLogger().log(level, "[AstralisSync] " + message);
    }
}
