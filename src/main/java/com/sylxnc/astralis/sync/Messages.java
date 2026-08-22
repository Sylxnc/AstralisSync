package com.sylxnc.astralis.sync;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central message styling: MiniMessage with gradients, prefix, placeholders,
 * optional action bar / title / sound per message.
 * <p>
 * Placeholder syntax in messages.yml: {name}, {server}, {rows}, ...
 * Sound syntax: "LEVEL_UP:1:1" (SOUND:volume:pitch), empty = silent.
 */
public final class Messages {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");
    private static final Pattern SOUND_PART = Pattern.compile("([A-Z0-9_.]+)(?::([0-9.]+))?(?::([0-9.]+))?");

    private final Plugin plugin;
    private final String rawPrefix;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public Messages(Plugin plugin) {
        this.plugin = plugin;
        this.rawPrefix = plugin.getConfig().getString("messages.prefix", "<gradient:#B14EFF:#00E0FF>Astral</gradient> <dark_gray>» </dark_gray>");
    }

    /** Renders a config message with placeholder substitution. */
    public Component render(String path, String fallback, Map<String, String> placeholders) {
        String raw = plugin.getConfig().getString("messages." + path, fallback);
        raw = applyPlaceholders(raw, placeholders);
        return mm.deserialize(rawPrefix + raw)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public Component renderRaw(String miniMessage) {
        return mm.deserialize(miniMessage)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public void send(Player player, String path, String fallback, Map<String, String> placeholders) {
        player.sendMessage(render(path, fallback, placeholders));
        playEffect(player, path);
    }

    public void send(Player player, String path, String fallback) {
        send(player, path, fallback, Map.of());
    }

    public void actionBar(Player player, Component component) {
        player.sendActionBar(component);
    }

    public void title(Player player, String miniTitle, String miniSub) {
        Title title = Title.title(
                mm.deserialize(applyPlaceholders(miniTitle, Map.of())),
                mm.deserialize(applyPlaceholders(miniSub, Map.of())),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1600), Duration.ofMillis(400)));
        player.showTitle(title);
    }

    private void playEffect(Player player, String path) {
        String soundSpec = plugin.getConfig().getString("messages." + path + ".sound", "");
        if (soundSpec == null || soundSpec.isEmpty()) {
            soundSpec = plugin.getConfig().getString("sounds.default", "ENTITY_EXPERIENCE_ORB_PICKUP:0.6:1.4");
        }
        if (soundSpec.isEmpty() || soundSpec.equalsIgnoreCase("none")) {
            return;
        }
        Matcher m = SOUND_PART.matcher(soundSpec);
        if (!m.find()) {
            return;
        }
        try {
            Sound sound = Sound.valueOf(m.group(1));
            float volume = m.group(2) != null ? Float.parseFloat(m.group(2)) : 1.0f;
            float pitch = m.group(3) != null ? Float.parseFloat(m.group(3)) : 1.0f;
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException ignored) {
            // unknown sound - silently skip
        }
    }

    private String applyPlaceholders(String input, Map<String, String> placeholders) {
        if (placeholders.isEmpty()) {
            return input;
        }
        Matcher m = PLACEHOLDER.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = placeholders.getOrDefault(key.toLowerCase(), m.group(0));
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
