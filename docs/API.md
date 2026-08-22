# Developer API

AstralalisSync exposes a small Java API for other plugins on the same server.

## Setup

Add the plugin jar as a compile dependency (or publish/consume via the GitHub
Packages coordinates shown in the README) and depend on it in your
`plugin.yml`:

```yaml
depend: [ AstralisSync ]
```

## Accessing the API

```java
import com.sylxnc.astralis.sync.api.AstralisSyncApi;
import com.sylxnc.astralis.sync.api.ApiProvider;

AstralisSyncApi api = ApiProvider.get();
if (api == null) {
    // AstralisSync not installed/enabled - degrade gracefully
}
```

## API surface

### Ender chest

| Method | Description |
|---|---|
| `int getEnderChestRows(UUID)` | Current rows of a player (DB-backed cache). |
| `int getMaxEnderChestRows()` | Configured network maximum. |
| `int upgradeEnderChestRows(UUID)` | Grants +1 row. Returns new count or `-1` at max / cancelled. Fires [`EnderChestUpgradeEvent`](#events). |

### Vouchers

| Method | Description |
|---|---|
| `void giveVoucher(Player, String id, int amount)` | Gives a configured voucher from `config.yml → vouchers`. |
| `boolean isVoucher(ItemStack)` | PDC-based voucher check. |

### Snapshots

| Method | Description |
|---|---|
| `void captureSnapshot(Player, String cause)` | Async capture with your custom cause label. |
| `boolean restoreSnapshot(Player, long id)` | **Main thread only.** Fires cancellable `SnapshotRestoreEvent`, auto-captures `pre-restore`, notifies webhooks and re-saves on success. |

### Raw data

| Method | Description |
|---|---|
| `CompletableFuture<byte[]> getPlayerData(UUID)` | Latest payload (Redis → MySQL). Versioned binary format of `SnapshotCodec` v3 — decode via `SnapshotCodec.decode(byte[])`. |
| `void savePlayer(Player)` | Persists current state to MySQL + Redis asynchronously. |

### Misc

* `String getServerId()` – configured backend id.

## Events

All events live in `com.sylxnc.astralis.sync.api.event`.

| Event | Thread | Cancellable | Fired when |
|---|---|---|---|
| `EnderChestUpgradeEvent` | main | ✅ | An EC row upgrade is about to be granted (`getOldRows()`/`getNewRows()`). Cancelling blocks it and the voucher is *not* consumed. |
| `VoucherRedeemEvent` | main | ✅ | A player clicks a voucher. Cancelling prevents redemption. |
| `SnapshotRestoreEvent` | main | ✅ | Before a snapshot is applied to a player. |
| `SnapshotCapturedEvent` | async | ❌ | After a snapshot was persisted (`getCause()`: `death`, `quit`, `manual`, `pre-restore`, or your API cause). |

Example listener:

```java
public final class NoRestoreListener implements Listener {
    @EventHandler(ignoreCancelled = true)
    public void onRestore(SnapshotRestoreEvent event) {
        if (event.getPlayer().getWorld().getName().equals("arena")) {
            event.setCancelled(true);
        }
    }
}
```

## Threading rules

* Everything may be called off-thread **except** `restoreSnapshot` (main thread enforced).
* Events marked "main" must be listened to normally (Bukkit delivers them on main); `SnapshotCapturedEvent` arrives async — do not touch Bukkit state inside.
