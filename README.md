<div align="center">

# AstralisSync

**Cross-server player data sync for Paper networks — MySQL + Redis**

Inventories · Ender Chests · XP · Snapshots · InvSee · Vouchers · Shop

[![Build](https://github.com/Sylxnc/AstralisSync/actions/workflows/build.yml/badge.svg)](../../actions)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.x-8A2BE2)](https://papermc.io)
[![Java](https://img.shields.io/badge/Java-25+-orange)](https://openjdk.org)

</div>

---

## ✨ Features

| Feature | Description |
|---|---|
| 🔄 **Full data sync** | Inventory (incl. armor, off-hand, held slot), ender chest, XP, health, hunger, potion effects, gamemode & location |
| 🗄️ **MySQL persistence** | HikariCP connection pool, versioned binary snapshots, upsert-safe |
| ⚡ **Redis cache + messaging** | 5-min hot cache, pub/sub with auto-reconnect, `REQ_SAVE` remote-save requests |
| 🔐 **Distributed locks** | `SET NX PX` login locks prevent dual-login data loss; auto-renew every 30 s |
| 💀 **Snapshot history** | Auto-capture before fatal damage, on quit, manually & pre-restore. Rolling limit via config. Browse + restore via GUI |
| 👁️ **Cross-server InvSee** | Live-editable mirror for local players, fresh read-only view of remote players |
| 📦 **Ender chest upgrades** | Row-based EC (1–6 rows) with mirror-back on close |
| 🎟️ **Vouchers** | PDC-tagged clickable items that survive sync; left *or* right click to redeem |
| 🛒 **Voucher shop** | Buy vouchers with items or XP levels via GUI |
| 🏆 **Advancements & stats sync** | Awarded criteria + untyped statistics follow the player |
| 🛡️ **Checksum integrity** | SHA-256 per payload, quarantine table, `loadAnyValid()` recovery, `verifyAll()` scan |
| 📣 **Discord webhooks** | Restores, purges, lock conflicts, corruption alerts |
| 🧩 **Developer API + Events** | `AstralisSyncApi`, `ApiProvider`, 4 cancellable/info events |
| 📊 **PlaceholderAPI** | `%astralissync_ecrows%`, `%astralissync_server%`, … |

## 🚀 Quick start

1. Drop `Astralissync-<version>.jar` into `plugins/` on **every** backend server.
2. Start once to generate `config.yml`, then set on each server:
   ```yaml
   server-id: "survival-1"   # must be UNIQUE per server!
   mysql: { host, database, username, password }
   redis: { host, password }
   ```
3. Restart. Tables are created automatically.

**Requirements:** Paper 1.21.x · Java 25+ · MySQL 8+/MariaDB · Redis 6+

## 📖 Commands

| Command | Permission | Description |
|---|---|---|
| `/astralissync status` | `astralissync.admin` | Connection & server status |
| `/astralissync save [player]` | `astralissync.admin` | Force-save one or all players |
| `/astralissync purge <player>` | `astralissync.admin` | Delete a player's synced data |
| `/astralissync voucher <id> [player] [n]` | `astralissync.admin` | Give a voucher |
| `/astralissync ec upgrade` / `ec set <p> <rows>` | `astralissync.admin` | Manage ender chest rows |
| `/snapshots [player]` · `save` · `restore <id>` | `astralissync.snapshots` | Snapshot GUI & restore |
| `/invsee <player>` | `astralissync.invsee` | Cross-server inventory view |
| `/vouchershop` | `astralissync.shop` (default: true) | Buy vouchers |
| `/syncexport export <player>` / `import <file>` | `astralissync.export` | JSON migration tool |

## ⚙️ Configuration

See [docs/CONFIGURATION.md](docs/CONFIGURATION.md) for every key with examples — feature toggles, ender chest rows, snapshot limits, voucher skins, shop prices, message gradients & sounds, webhooks.

## 🧩 Developer API

```xml
<repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/Sylxnc/AstralisSync</url>
</repository>

<dependency>
    <groupId>com.sylxnc.astralis</groupId>
    <artifactId>sync</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```

```java
AstralisSyncApi api = ApiProvider.get();
int rows = api.getEnderChestRows(player.getUniqueId());
api.captureSnapshot(player, "my-plugin");

@EventHandler
void onRestore(SnapshotRestoreEvent event) {
    if (event.getPlayer().hasPermission("myplugin.norestore")) {
        event.setCancelled(true);
    }
}
```

Full guide: [docs/API.md](docs/API.md)

## 🏗️ Architecture

```
Player joins
  │
  ├─ AsyncPreLogin ──► Redis LOCK (SET NX PX 90s) ──► denied if another server owns it
  ├─ Join ──────────► load: Redis cache ──► MySQL ──► apply on main thread
  │                   + advancements/statistics restore
  ├─ Playing ───────► autosave (5 min) + lock renewal (30 s) + pre-death snapshots
  └─ Quit ──────────► snapshot ──► save (async) ──► pub/sub broadcast ──► unlock
```

Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## 🤝 Contributing

Branch-Modell (`main` ← `staging` ← `wip/<thema>`), SemVer-Regeln und der Release-Ablauf stehen in [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md). `mvn clean package` muss grün bleiben; die Shade-Relocation-Regeln in `pom.xml` nicht antasten.

## 📄 License

[Attribution License](LICENSE) © Sylxnc — free to use and modify, **credit "Includes AstralisSync by Sylxnc" required** in any distribution, even after modifications.
