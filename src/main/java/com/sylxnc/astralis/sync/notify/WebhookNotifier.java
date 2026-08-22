package com.sylxnc.astralis.sync.notify;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Discord webhook notifier for security-relevant events
 * (snapshot restores, data purges, lock conflicts, corruption).
 */
public final class WebhookNotifier {

    private final Plugin plugin;
    private final String url;
    private final HttpClient client;

    public WebhookNotifier(Plugin plugin) {
        this.plugin = plugin;
        this.url = plugin.getConfig().getString("discord-webhook.url", "");
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public boolean isEnabled() {
        return url != null && !url.isEmpty();
    }

    /** Sends an embed-style webhook message async; never blocks or throws. */
    public void send(String title, String description, int colorRgb) {
        if (!isEnabled()) {
            return;
        }
        String json = "{\"embeds\":[{\"title\":" + jsonString(title)
                + ",\"description\":" + jsonString(description)
                + ",\"color\":" + colorRgb
                + ",\"footer\":{\"text\":\"AstralisSync\"}}]}";

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
                client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                plugin.getLogger().warning("Webhook failed: " + e.getMessage());
            }
        });
    }

    public void snapshotRestored(Player player, long snapshotId) {
        send("\uD83D\uDD01 Snapshot wiederhergestellt",
                "**" + player.getName() + "** wurde auf Snapshot #" + snapshotId + " zurückgesetzt.",
                0x3498DB);
    }

    public void lockConflict(String playerName) {
        send("\u26A0 Login blockiert",
                "**" + playerName + "** versuchte zu joinen, während die Daten auf einem anderen Server gesperrt sind.",
                0xE67E22);
    }

    public void dataPurged(String playerName, boolean found) {
        send("\uD83D\uDDD1 Spielerdaten gelöscht",
                "Daten von **" + playerName + "**: " + (found ? "gelöscht." : "nicht gefunden."),
                found ? 0xC0392B : 0x95A5A6);
    }

    public void corruptData(java.util.UUID uuid) {
        send("\uD83D\uDEA1 Korrupte Daten erkannt",
                "Prüfsummen-Fehler bei **" + uuid + "**. Zeile wurde in `corrupted_player_data` gesichert.",
                0xE74C3C);
    }

    private static String jsonString(String s) {
        String escaped = s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }
}
