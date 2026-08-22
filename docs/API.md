# Developer API

Astralissync exposes a small Java API for plugins running on the same server.

## Setup

Add the plugin jar as a compile-time dependency and declare the dependency in your `plugin.yml`:

```yaml
depend: [ AstralisSync ]
```

Maven coordinates (publish via GitHub Packages or install the jar locally with `mvn install`):

```xml
<dependency>
    <groupId>com.sylxnc.astralis</groupId>
    <artifactId>sync</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```

## Accessing the API

```java
import com.sylxnc.astralis.sync.api.AstralisSyncApi;
import com.sylxnc.astralis.sync.api.ApiProvider;

AstralisSyncApi api = ApiProvider.get();
if (api == null) {
    // AstralisSync is not installed or not enabled; degrade gracefully.
}
```

The instance is registered on enable and cleared on disable of AstralisSync.

## Methods

### Ender chest

| Method | Description |
|---|---|
| `int getEnderChestRows(UUID playerId)` | Current row count for a player. |
| `int getMaxEnderChestRows()` | Configured network maximum. |
| `int upgradeEnderChestRows(UUID playerId)` | Grants one additional row. Returns the new count, or `-1` when already at maximum or cancelled by an event listener. |

### Vouchers

| Method | Description |
|---|---|
| `void giveVoucher(Player player, String id, int amount)` | Gives a voucher defined in `config.yml` under `vouchers.<id>`. |
| `boolean isVoucher(ItemStack item)` | Persistent-data-based voucher check. |

### Snapshots

| Method | Description |
|---|---|
| `void captureSnapshot(Player player, String cause)` | Captures the current state asynchronously using your custom cause label. |
| `boolean restoreSnapshot(Player player, long snapshotId)` | Restores the given snapshot. Must run on the main thread. Fires a cancellable event, captures a `pre-restore` safety snapshot, sends a webhook notification and re-saves the player on success. |

### Raw data access

| Method | Description |
|---|---|
| `CompletableFuture<byte[]> getPlayerData(UUID playerId)` | Latest payload, resolved from Redis first, then MySQL. Use `SnapshotCodec.decode(byte[])` to inspect it. |
| `void savePlayer(Player player)` | Persists the current state to MySQL and Redis asynchronously. |

### Miscellaneous

- `String getServerId()` returns the configured backend identifier.

## Events

All events are located in `com.sylxnc.astralis.sync.api.event`.

| Event | Thread | Cancellable | Fired when |
|---|---|---|---|
| `EnderChestUpgradeEvent` | main | yes | An ender chest upgrade is about to be granted. Cancelling blocks it without consuming the voucher. Provides old and new row counts. |
| `VoucherRedeemEvent` | main | yes | A player clicks a voucher. Cancelling prevents redemption. |
| `SnapshotRestoreEvent` | main | yes | Before a snapshot is applied to a player. |
| `SnapshotCapturedEvent` | async | no | After a snapshot was persisted. Cause is one of `death`, `quit`, `manual`, `pre-restore` or your custom API cause. |

Example:

```java
public final class ArenaRules implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onRestore(SnapshotRestoreEvent event) {
        if (event.getPlayer().getWorld().getName().equals("arena")) {
            event.setCancelled(true);
        }
    }
}
```

## Threading rules

- All methods may be called from any thread except `restoreSnapshot`, which enforces the main thread.
- Main-thread events must be consumed through normal Bukkit listeners; do not touch Bukkit state inside the asynchronous `SnapshotCapturedEvent`.
