package com.sylxnc.astralis.sync.snapshot;

import com.sylxnc.astralis.sync.Main;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/** Paginated GUI listing a player's snapshots with restore action. */
public final class SnapshotBrowserMenu implements com.sylxnc.astralis.sync.gui.GuiManager.Menu {

    public static final int PAGE_SIZE = 36;

    private final Main plugin;
    private final Player viewer;
    private final UUID target;
    private final String targetName;
    private int page;

    private Inventory inventory;

    public SnapshotBrowserMenu(Main plugin, Player viewer, UUID target, String targetName, int page) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.target = target;
        this.targetName = targetName;
        this.page = Math.max(page, 0);
    }

    /** Loads data async and opens the menu on the main thread. */
    public void openAsync() {
        plugin.getSnapshotManager().listAsync(target, PAGE_SIZE, page * PAGE_SIZE, entries ->
                plugin.getSnapshotManager().countAsync(target, count -> render(entries, count)));
    }

    private void render(List<SnapshotStore.Entry> entries, int total) {
        if (!viewer.isOnline()) {
            return;
        }
        int pages = Math.max(1, (int) Math.ceil(total / (double) PAGE_SIZE));
        page = Math.min(page, pages - 1);

        inventory = Bukkit.createInventory(null, 54,
                plugin.getMessages().renderRaw("<gradient:#B14EFF:#00E0FF>Snapshots</gradient> <dark_gray>·</dark_gray> " +
                        net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                                "<gray>" + targetName + "</gray>").insertion(targetName)
                        + " <dark_gray>(" + (page + 1) + "/" + pages + ")</dark_gray>"));

        var gui = plugin.getGuiManager();
        int slot = 0;
        for (SnapshotStore.Entry entry : entries) {
            Material icon = switch (entry.cause()) {
                case "death" -> Material.SKELETON_SKULL;
                case "manual" -> Material.PAPER;
                case "quit" -> Material.ENDER_PEARL;
                default -> Material.CHEST;
            };
            inventory.setItem(slot++, gui.button(icon,
                    "<gradient:#FFD700:#FF8C00>#" + entry.id() + "</gradient>",
                    java.util.List.of(
                            "<gray>Grund:</gray> <white>" + entry.cause() + "</white>",
                            "<gray>Server:</gray> <white>" + entry.serverId() + "</white>",
                            "",
                            "<yellow>Linksklick:</yellow> <gray>Vorschau</gray>",
                            "<red>Rechtsklick:</red> <gray>Wiederherstellen</gray>"),
                    "snapshot:" + entry.id()));
        }

        // Navigation bar
        ItemStack filler = gui.button(Material.GRAY_STAINED_GLASS_PANE, " ", List.of(), "noop");
        for (int i = 45; i < 54; i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, filler);
            }
        }
        if (page > 0) {
            inventory.setItem(45, gui.button(Material.ARROW, "<yellow>« Zurück", List.of(), "prev:0"));
        }
        if (page < pages - 1) {
            inventory.setItem(53, gui.button(Material.ARROW, "<yellow>Weiter »", List.of(), "next:0"));
        }
        inventory.setItem(49, gui.button(Material.BARRIER, "<red>Schließen", List.of(), "close:0"));

        plugin.getGuiManager().open(viewer, inventory, this);
    }

    @Override
    public Inventory inventory() {
        return inventory;
    }

    @Override
    public void onClick(Player player, String action, long arg) {
        switch (action) {
            case "noop" -> {
            }
            case "close" -> player.closeInventory();
            case "prev" -> new SnapshotBrowserMenu(plugin, viewer, target, targetName, page - 1).openAsync();
            case "next" -> new SnapshotBrowserMenu(plugin, viewer, target, targetName, page + 1).openAsync();
            case "snapshot" -> {
                if (!player.hasPermission("astralissync.admin") && !player.getUniqueId().equals(target)) {
                    player.sendMessage("§cKein Zugriff.");
                    return;
                }
                if (arg > 0 && player.isOnline()) {
                    player.closeInventory();
                    boolean ok = plugin.getSnapshotManager().restore(player, arg);
                    if (ok) {
                        plugin.getMessages().send(player, "snapshot-restored",
                                "<green>Snapshot <white>#" + arg + "</white> wiederhergestellt! Dein vorheriger Stand wurde gesichert.</green>");
                        plugin.getWebhookNotifier().snapshotRestored(player, arg);
                        plugin.getSyncService().savePlayer(player);
                    } else {
                        plugin.getMessages().send(player, "snapshot-missing", "<red>Snapshot nicht gefunden.</red>");
                    }
                }
            }
            default -> {
            }
        }
    }
}
