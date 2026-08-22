# AstralisSync

Cross-server player data synchronization for Paper networks, backed by MySQL and Redis.

Astralissync keeps one authoritative snapshot per player and replicates it across every backend server in a network. It covers the full player state — inventory, ender chest, experience, attributes, status effects and position — and adds operational tooling on top: snapshot history with a restore GUI, live cross-server inventory inspection, purchasable ender chest rows, redeemable vouchers, integrity checks and Discord notifications.

## Features

**Synchronization**

- Inventory including armor, off-hand and held slot
- Ender chest (including purchased extra rows)
- Experience, health and max-health modifiers, hunger and saturation
- Active potion effects
- Game mode, flight and gliding state
- Last known location
- Advancements (awarded criteria) and untyped statistics

**Infrastructure**

- MySQL persistence via HikariCP; Redis cache and pub/sub messaging
- Distributed login locks that prevent two servers from loading the same player simultaneously
- Automatic lock renewal while a player is online

**Tooling**

- Snapshot history: captured before fatal damage, on quit and manually; browsable and restorable through an in-game GUI with a configurable rolling limit
- Cross-server `/invsee`: editable live view for local players, read-only view of remote players
- Ender chest row upgrades (1–6 rows) driven by config or vouchers
- Vouchers: persistent clickable items redeemable with left or right click
- In-game voucher shop accepting items or XP levels
- SHA-256 payload checksums with quarantine of corrupted rows
- Discord webhook notifications for restores, purges, lock conflicts and detected corruption
- JSON export/import for moving players between networks

**Extensibility**

- Developer API (`AstralisSyncApi`) with four events
- PlaceholderAPI expansion (`%astralissync_ecrows%`, `%astralissync_server%`, …)

## Requirements

| Component | Version |
|---|---|
| Paper | 1.21.x |
| Java | 25 or newer |
| MySQL / MariaDB | 8.0+ / 10.6+ |
| Redis | 6.0+ |

## Installation

1. Place `AstralisSync.jar` in the `plugins/` directory of **every** backend server.
2. Start each server once to generate `plugins/AstralisSync/config.yml`.
3. Configure a unique `server-id` per server plus your MySQL and Redis credentials.
4. Restart. All database tables are created automatically.

Full configuration reference: [docs/CONFIGURATION.md](docs/CONFIGURATION.md)

## Commands

| Command | Permission | Description |
|---|---|---|
| `/astralissync status` | `astralissync.admin` | Show connection and server status |
| `/astralissync save [player]` | `astralissync.admin` | Force-save one or all players |
| `/astralissync purge <player>` | `astralissync.admin` | Delete a player's synchronized data |
| `/astralissync voucher <id> [player] [amount]` | `astralissync.admin` | Give a voucher item |
| `/astralissync ec upgrade` · `ec set <player> <rows>` | `astralissync.admin` | Manage ender chest rows |
| `/snapshots [player]`, `/snapshots save`, `/snapshots restore <id>` | `astralissync.snapshots` | Browse, create and restore snapshots |
| `/invsee <player>` | `astralissync.invsee` | Cross-server inventory view |
| `/vouchershop` | `astralissync.shop` (default: true) | Open the voucher shop |
| `/syncexport export <player>`, `/syncexport import <file>` | `astralissync.export` | JSON export/import for migrations |

## Documentation

| Document | Contents |
|---|---|
| [Configuration](docs/CONFIGURATION.md) | Every configuration key with defaults and examples |
| [Developer API](docs/API.md) | API access, methods, events, threading rules |
| [Architecture](docs/ARCHITECTURE.md) | Data model, snapshot format, lifecycle, locking |
| [Contributing](docs/CONTRIBUTING.md) | Branch model, versioning, release process |

## Building

```bash
mvn clean package
```

The build produces a self-contained jar at `target/sync-<version>.jar`. Runtime libraries are shaded and relocated; no external dependencies need to be installed on the server.

## License

This project is licensed under the [Attribution License](LICENSE). Use and modification are permitted provided the credit *"Includes AstralisSync by Sylxnc"* remains visible in any distribution, including modified versions.
