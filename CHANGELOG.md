# Changelog

All notable changes to AstralisSync are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project adheres to [Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-08-23

### Added

- Cross-server synchronization of inventory, ender chest, experience, health, hunger, potion effects, game mode and location, backed by MySQL (HikariCP) with Redis caching, pub/sub messaging and distributed login locks
- Automatic lock renewal while a player is online; joins are denied when another server holds the data
- Snapshot history captured before fatal damage, on quit, manually and before restores, with a configurable rolling limit and a paginated restore GUI
- Live cross-server `/invsee` with an editable mirror for local players and refreshed read-only views for remote players via `REQ_SAVE` messaging
- Ender chest row upgrades (one to six rows) persisted in `player_meta`
- Clickable vouchers identified through persistent data containers, redeemable with left or right click, plus an in-game shop accepting items or XP levels
- Advancement and untyped statistics synchronization stored in dedicated tables
- SHA-256 payload checksums with quarantine of corrupted rows and recovery helpers (`loadAnyValid`, `verifyAll`)
- Discord webhook notifications for restores, purges, lock conflicts and corruption
- JSON export and import commands for migrating players between networks
- Developer API (`AstralisSyncApi`, `ApiProvider`) with events for ender chest upgrades, voucher redemptions and snapshot lifecycle
- PlaceholderAPI expansion exposing ender chest rows, maximum rows and server identifiers
- MiniMessage-based configurable messages with gradients and per-message sounds
