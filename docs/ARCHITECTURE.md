# Architecture

## Overview

Astralissync stores one authoritative snapshot per player in MySQL and uses Redis for two purposes: a short-lived cache so that joins rarely hit the database, and pub/sub messaging between backend servers.

```
                ┌───────────────────────┐
   lobby-1 ────►│         Redis         │◄──── survival-1
   (Paper)      │  cache · locks · bus  │      (Paper)
                └───────────┬───────────┘
                            │ async I/O (HikariCP)
                      ┌─────▼─────┐
                      │   MySQL   │
                      └───────────┘
```

## Data model

| Table | Purpose |
|---|---|
| `player_data` | One row per player: binary snapshot, last known name, last server, monotonic `data_version`, SHA-256 checksum. |
| `player_meta` | Purchased ender chest rows. |
| `player_snapshots` | Rolling history with cause and origin server; pruned to the configured limit. |
| `advancement_data` / `statistics_data` | GZIP-compressed progress payloads. |
| `corrupted_player_data` | Quarantine for checksum mismatches; recoverable via `loadAnyValid`. |

## Snapshot format

Payloads are versioned (`[int version][int flags]...`). Version 3 layout:

```
storage items, armor items, off hand,
ender chest rows + slots + items,
held slot,
base max health, health,
food, saturation, burning flag,
total XP, level, XP progress,
effect count + effects,
world name, x, y, z, yaw, pitch,
game mode, flying, gliding
```

Items are stored with `ItemStack#serializeAsBytes()` (vanilla NBT), making payloads portable across servers running compatible Minecraft data versions. Unknown newer versions are rejected; older versions are read through the flags mask.

## Player lifecycle

```
AsyncPlayerPreLoginEvent
  ├─ SET astralissync:lock:<uuid> <server-id> NX PX 90000
  │     OK       → continue; ownership recorded locally
  │     rejected → deny join ("data locked by another server") + webhook

PlayerJoinEvent
  ├─ load: Redis GET → miss → MySQL SELECT → backfill cache
  ├─ apply snapshot on the main thread
  └─ restore advancements and statistics

PlayerQuitEvent
  ├─ capture "quit" snapshot
  ├─ save asynchronously (MySQL upsert, Redis cache write, SAVE broadcast)
  └─ after one second: release lock, clear local caches

Repeating tasks
  ├─ SaveTask       every autosave-interval-ticks: save all online players
  └─ LockRenewTask  every 30 s: extend locks of online players (TTL 90 s)
```

## Consistency rules

- All database and network I/O runs on Bukkit's async scheduler.
- Snapshot captures per player are serialized through a pending set to prevent duplicates.
- Locks fail closed: if Redis is unreachable during login, the join is denied rather than risking two servers loading the same player.

## Remote inventory inspection

1. A viewer runs `/invsee <name>` on server A while the target plays on B or is offline.
2. Server A resolves the UUID from MySQL and reads the Redis cache.
3. On a miss it publishes `REQ_SAVE`; the lock owner saves immediately, refreshing the shared cache.
4. Server A opens a read-only view from the refreshed data. Local players instead receive an editable mirror written back every two seconds and on close.

## Packaging

HikariCP and Jedis are shaded into the plugin jar and relocated under `com.sylxnc.astralis.sync.lib`, leaving no external runtime dependencies. The MySQL driver is expected to be provided by the server environment.
