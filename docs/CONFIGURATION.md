# Configuration Reference

All settings live in `plugins/Astralissync/config.yml`. Defaults are generated on first start.

## General

| Key | Default | Description |
|---|---:|---|
| `server-id` | `lobby-1` | Unique identifier of this backend server. Must differ on every server in the network. |
| `autosave-interval-ticks` | `6000` | Interval for periodic saves of all online players (20 ticks = 1 second; 6000 = 5 minutes). |

## MySQL

```yaml
mysql:
  host: "127.0.0.1"
  port: 3306
  database: "astralissync"
  username: "root"
  password: ""
  use-ssl: false
  pool-size: 10
```

`pool-size` is the HikariCP maximum pool size. Ten connections comfortably serve networks with up to roughly ten backend servers; increase only if you observe connection wait warnings.

## Redis

```yaml
redis:
  host: "127.0.0.1"
  port: 6379
  password: ""
  ssl: false
  cache-ttl-millis: 300000
```

`cache-ttl-millis` controls how long snapshots remain in the Redis cache. MySQL remains the source of truth at all times; a cold or empty cache only costs a database query on join.

## Feature toggles

Each entry enables one part of the synchronized player state:

```yaml
features:
  inventory: true      # storage, armor, off hand, held slot
  enderchest: true     # ender chest including purchased rows
  experience: true
  health: true         # base max-health modifier and current health
  hunger: true         # food level and saturation
  potion-effects: true
  location: true       # teleport back to last position on join
  gamemode: true       # game mode, flying, gliding
```

## Ender chest upgrades

```yaml
enderchest:
  default-rows: 3      # starting rows (1-3)
  max-rows: 6          # purchasable maximum (3-6)
```

Rows beyond three are provided through a custom inventory whose contents are mirrored back into the vanilla ender chest when closed, so they persist and synchronize like any other data.

## Snapshots

```yaml
snapshots:
  max-per-player: 10   # rolling history limit (1-200)
```

Snapshots are captured automatically before fatal damage (`death`), on quit (`quit`) and before every restore (`pre-restore`). Players can create manual snapshots via `/snapshots save`. The oldest entries beyond the limit are deleted after each capture.

## Vouchers

Vouchers are defined under `vouchers.<id>` and given with `/astralissync voucher <id> [player] [amount]`:

```yaml
vouchers:
  ec-row:
    material: ENDER_EYE   # item type
    glow: true            # enchantment glint override
    name: "<gradient:#B14EFF:#00E0FF>Enderchest Upgrade</gradient>"
    lore:
      - "<gray>Right click to redeem.</gray>"
      - "<gray>Adds</gray> <gold>+1 row</gold> <gray>to your ender chest.</gray>"
    type: ENDERCHEST_ROW  # or ENDERCHEST_MAX
```

Any additional entry becomes instantly giveable — new voucher types require no code changes.

## Shop

Prices for the `/vouchershop` GUI:

```yaml
shop:
  ec-row:
    cost-type: ITEM       # ITEM or XP
    cost-item: DIAMOND    # used when cost-type is ITEM
    cost-amount: 16       # item count or XP levels
    slot: 11              # optional slot in the 27-slot menu
```

## Messages and sounds

Messages use MiniMessage syntax, including gradients and hex colors. `{placeholders}` are substituted where supported.

```yaml
messages:
  prefix: "<gradient:#B14EFF:#00E0FF>Astral</gradient> <dark_gray>» </dark_gray>"
sounds:
  default: "ENTITY_EXPERIENCE_ORB_PICKUP:0.6:1.4"
```

Individual messages can define their own sound using the format `SOUND:volume:pitch`:

```yaml
messages:
  snapshot-restored:
    sound: "UI_TOAST_CHALLENGE_COMPLETE:1:1.2"
```

## Integrations

```yaml
discord-webhook:
  url: ""                # Discord webhook URL; empty disables notifications

advancements:
  enabled: true

statistics:
  enabled: true

integrity:
  checksums: true        # SHA-256 verification and quarantine of corrupted rows
```

Webhook notifications are sent for snapshot restores, blocked logins caused by lock conflicts, data purges and detected checksum corruption.
