# Changelog

All notable changes to AstralisSync are documented here.
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.0.0] - 2026-08-23

### Added
- Cross-server sync of inventory, ender chest, XP, health, hunger, potion effects, gamemode & location (MySQL + HikariCP, Redis cache/pub-sub)
- Distributed login locks (`SET NX PX`) with auto-renewal and fail-closed behavior
- Snapshot history: auto pre-death, quit, manual & pre-restore captures; rolling limit; paginated GUI with restore
- Live cross-server `/invsee` (editable local mirror, read-only remote via `REQ_SAVE` pub/sub)
- Ender chest row upgrades (1–6 rows) with custom inventory mirroring
- Clickable vouchers (PDC-tagged, left/right-click redeem) + GUI shop (item & XP costs)
- Advancements & untyped statistics sync
- SHA-256 payload checksums with quarantine table and `loadAnyValid()` / `verifyAll()` recovery
- Discord webhook notifications (restore, purge, lock conflict, corruption)
- JSON export/import (`/syncexport`) for network migration
- Developer API (`AstralisSyncApi`, `ApiProvider`) + events (`EnderChestUpgradeEvent`, `VoucherRedeemEvent`, `SnapshotRestoreEvent`, `SnapshotCapturedEvent`)
- PlaceholderAPI expansion (`%astralissync_*%`)
- MiniMessage-based configurable messages with gradients and per-message sounds
