# Configuration Reference

All keys live in `plugins/Astralissync/config.yml`. Defaults are generated on first start.

## Core

| Key | Default | Description |
|---|---|---|
| `server-id` | `lobby-1` | **Unique** id of this backend server in the network. Must differ on every server. |
| `autosave-interval-ticks` | `6000` | Periodic full save of all online players (20 ticks = 1 s; 6000 = 5 min). |

## mysql

| Key | Default |
|---|---|
| `host` | `127.0.0.1` |
| `port` | `3306` |
| `database` | `astralissync` |
| `username` / `password` | `root` / empty |
| `use-ssl` | `false` |
| `pool-size` | `10` (HikariCP max pool) |

## redis

| Key | Default | Description |
|---|---|---|
| `host` / `port` | `127.0.0.1:6379` | |
| `password` / `ssl` | empty / false | |
| `cache-ttl-millis` | `300000` | Snapshot cache TTL. MySQL is always the source of truth. |

## features

Toggle what gets synced per player:

```yaml
features:
  inventory: true      # storage + armor + off hand + held slot
  enderchest: true     # incl. purchased rows
  experience: true
  health: true         # base max-health modifier + current health
  hunger: true         # food + saturation
  potion-effects: true
  location: true       # teleport back to last position on join
  gamemode: true       # gamemode, flying, gliding
```

## enderchest

| Key | Default | Description |
|---|---|---|
| `default-rows` | `3` | Rows a new player starts with (1–3). |
| `max-rows` | `6` | Purchasable maximum (3–6). Rows 4+ use a custom inventory mirrored into vanilla storage on close. |

## snapshots

| Key | Default | Description |
|---|---|---|
| `max-per-player` | `10` | Rolling history limit (1–200). Oldest entries are pruned after each capture. |

Snapshots are captured automatically before fatal damage (`death`), on quit (`quit`), before every restore (`pre-restore`) and via `/snapshots save` (`manual`).

## vouchers

Each entry is giveable via `/astralissync voucher <id>`:

```yaml
vouchers:
  ec-row:
    material: ENDER_EYE   # item type
    glow: true            # enchantment glint override
    name: "<gradient:#B14EFF:#00E0FF>Enderchest Upgrade</gradient>"
    lore:
      - "<gray>Rechtsklick zum Einlösen.</gray>"
    type: ENDERCHEST_ROW  # or ENDERCHEST_MAX
```

## shop

GUI prices for `/vouchershop` (27-slot menu):

```yaml
shop:
  ec-row:
    cost-type: ITEM       # ITEM | XP
    cost-item: DIAMOND    # only for ITEM
    cost-amount: 16       # items or XP levels
    slot: 11              # optional GUI slot (0-26)
```

## messages & sounds

MiniMessage with gradients/hex colors. `{placeholders}` are substituted.

```yaml
messages:
  prefix: "<gradient:#B14EFF:#00E0FF>Astral</gradient> <dark_gray>» </dark_gray>"
sounds:
  default: "ENTITY_EXPERIENCE_ORB_PICKUP:0.6:1.4"   # SOUND:volume:pitch
# Per-message overrides:
messages:
  snapshot-restored:
    sound: "UI_TOAST_CHALLENGE_COMPLETE:1:1.2"
```

## discord-webhook

```yaml
discord-webhook:
  url: ""   # Discord webhook URL; empty = disabled
```

Events sent: snapshot restores 🔁, blocked logins (lock conflicts) ⚠️, data purges 🗑️, checksum corruption 🚨.

## advancements / statistics / integrity

```yaml
advancements:  { enabled: true }
statistics:    { enabled: true }   # untyped statistics
integrity:     { checksums: true } # SHA-256 verification + quarantine
```
