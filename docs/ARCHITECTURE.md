# Architecture

## Overview

Astralalisync keeps one authoritative snapshot per player in **MySQL** and uses
**Redis** for two things: a short-lived hot cache (so joins usually never touch
MySQL) and pub/sub messaging between backend servers.

```
                    ┌──────────────┐
   lobby-1 ────────►│              │◄──────── survival-1
   (Paper)          │    Redis     │         (Paper)
                    │ cache + lock │
                    │  + pub/sub   │
                    └──────┬───────┘
                           │ async I/O (HikariCP pool)
                    ┌──────▼───────┐
                    │    MySQL     │
                    └──────────────┘
```

## Data model

| Table | Purpose |
|---|---|
| `player_data` | One row per player: binary snapshot (`LONGBLOB`), `last_name`, `last_server`, monotonically increasing `data_version`, SHA-256 `checksum`. |
| `player_meta` | Purchased ender chest rows. |
| `player_snapshots` | Rolling history (`cause`, `server_id`, payload). Pruned to `snapshots.max-per-player`. |
| `advancement_data` / `statistics_data` | GZIP-compressed progress payloads. |
| `corrupted_player_data` | Quarantine for checksum mismatches (manual recovery via `loadAnyValid`). |

## Snapshot format (v3)

`[int version][int flags][storage items][armor items][offhand][ecRows int][ecSlots int][ec items][heldSlot int][baseMaxHealth d][health d][food i][saturation f][burning i][xpTotal i][level i][xpProgress f][effectCount i][effects…][world utf][x/y/z d][yaw/pitch f][gamemode utf][flying b][gliding b]`

Items are stored with `ItemStack#serializeAsBytes()` (vanilla NBT), so payloads
are portable across servers running compatible Minecraft data versions.
`flags` allows older payloads to be read unconditionally; unknown *newer*
versions are rejected.

## Join / quit lifecycle

```
AsyncPlayerPreLoginEvent (LOWEST)
  └─ Redis SET astralissync:lock:<uuid> <server-id> NX PX 90000
       ├─ OK      → continue, remember ownership locally
       └─ not OK  → KICK_OTHER ("data locked by another server") + webhook

PlayerJoinEvent
  ├─ load: Redis GET data:<uuid> → miss → MySQL SELECT → backfill cache
  ├─ apply on main thread (SnapshotCodec.apply)
  └─ restore advancements/statistics

PlayerQuitEvent
  ├─ capture "quit" snapshot
  ├─ save (async): MySQL upsert + Redis SETEX cache + PUBLISH SAVE:<server>:<uuid>
  └─ after 20 ticks: release lock + invalidate local caches

Timers
  ├─ SaveTask       every autosave-interval-ticks → save all online players
  └─ LockRenewTask  every 30 s → PEEXPIRE locks of online players (TTL is 90 s)
```

## Concurrency rules

* All MySQL/Redis I/O happens on Bukkit's async scheduler — never on the main thread.
* Snapshot writes serialize per player via a `pending` map to avoid duplicate captures.
* Locks fail **closed**: if Redis is unreachable at login, the join is denied rather than risking two servers loading the same inventory.

## Remote InvSee protocol

1. Viewer runs `/invsee <name>` on server A; the player is online on B (or offline).
2. A resolves the UUID from MySQL (`last_name`) and reads the Redis cache.
3. If absent, A publishes `REQ_SAVE:<A>:<uuid>`; B (lock owner) saves immediately, refreshing the shared cache.
4. A re-reads the cache/DB and opens a read-only view. Local players get an editable mirror written back every 2 s and on close.

## Shading & relocation

HikariCP and Jedis are shaded into the plugin jar under
`com.sylxnc.astralis.sync.lib.{hikari,jedis}` so the jar has no external
runtime dependencies. The MySQL driver is expected to be provided by Paper.
