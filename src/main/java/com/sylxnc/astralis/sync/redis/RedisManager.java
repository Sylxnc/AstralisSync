package com.sylxnc.astralis.sync.redis;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Redis layer: data cache, distributed login locks and pub/sub messaging
 * between backend servers.
 */
public final class RedisManager {

    public static final String CHANNEL_UPDATES = "astralissync:updates";
    private static final String KEY_DATA = "astralissync:data:";
    private static final String KEY_LOCK = "astralissync:lock:";

    /** Lock TTL. A server holding the lock renews it every 30s (see LockRenewTask). */
    private static final Duration LOCK_TTL = Duration.ofSeconds(90);

    private final Plugin plugin;
    private final String serverId;
    private volatile JedisPool pool;
    private volatile UpdateSubscriber subscriber;
    private volatile Thread subscriberThread;

    public RedisManager(Plugin plugin) {
        this.plugin = plugin;
        this.serverId = plugin.getConfig().getString("server-id", "unknown");
    }

    public boolean connect() {
        try {
            FileConfiguration cfg = plugin.getConfig();
            HostAndPort address = new HostAndPort(
                    cfg.getString("redis.host", "127.0.0.1"),
                    cfg.getInt("redis.port", 6379));

            DefaultJedisClientConfig.Builder clientConfig = DefaultJedisClientConfig.builder()
                    .connectionTimeoutMillis(5000)
                    .socketTimeoutMillis(5000);
            String password = cfg.getString("redis.password", "");
            if (!password.isEmpty()) {
                clientConfig.password(password);
            }
            if (cfg.getBoolean("redis.ssl", false)) {
                clientConfig.ssl(true);
            }

            this.pool = new JedisPool(address, clientConfig.build());
            try (Jedis jedis = pool.getResource()) {
                jedis.ping();
            }
            plugin.getLogger().info("Connected to Redis.");
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Redis connection failed: " + e.getMessage());
            close();
            return false;
        }
    }

    /* ------------------------------------------------------------------
     * Player data cache
     * ------------------------------------------------------------------ */

    public byte[] getCachedData(UUID uuid) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.get((KEY_DATA + uuid).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().warning("Redis cache read failed: " + e.getMessage());
            return null;
        }
    }

    public void cacheData(UUID uuid, byte[] payload) {
        try (Jedis jedis = pool.getResource()) {
            byte[] key = (KEY_DATA + uuid).getBytes(StandardCharsets.UTF_8);
            jedis.set(key, payload);
            jedis.pexpire(key, plugin.getConfig().getLong("redis.cache-ttl-millis", 300000L));
        } catch (Exception e) {
            plugin.getLogger().warning("Redis cache write failed: " + e.getMessage());
        }
    }

    public void invalidateCache(UUID uuid) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del((KEY_DATA + uuid).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // cache loss is non-fatal
        }
    }

    /* ------------------------------------------------------------------
     * Distributed lock (SET NX EX)
     * ------------------------------------------------------------------ */

    public boolean tryLock(UUID uuid) {
        try (Jedis jedis = pool.getResource()) {
            String key = KEY_LOCK + uuid;
            String result = jedis.set(key, serverId,
                    new redis.clients.jedis.params.SetParams().nx().px(LOCK_TTL.toMillis()));
            return "OK".equals(result) || serverId.equals(jedis.get(key));
        } catch (Exception e) {
            plugin.getLogger().severe("Redis lock failed: " + e.getMessage());
            return false; // fail closed - do not risk duplicated inventories
        }
    }

    public boolean renewLock(UUID uuid) {
        try (Jedis jedis = pool.getResource()) {
            String key = KEY_LOCK + uuid;
            String owner = jedis.get(key);
            if (!serverId.equals(owner)) {
                return false;
            }
            jedis.pexpire(key, LOCK_TTL.toMillis());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void unlock(UUID uuid) {
        try (Jedis jedis = pool.getResource()) {
            String key = KEY_LOCK + uuid;
            if (serverId.equals(jedis.get(key))) {
                jedis.del(key);
            }
        } catch (Exception ignored) {
        }
        invalidateCache(uuid);
    }

    /** True when another server currently holds the login lock for the player. */
    public boolean isLockedElsewhere(UUID uuid) {
        try (Jedis jedis = pool.getResource()) {
            String owner = jedis.get(KEY_LOCK + uuid);
            return owner != null && !serverId.equals(owner);
        } catch (Exception e) {
            return false;
        }
    }

    /* ------------------------------------------------------------------
     * Pub/Sub
     * ------------------------------------------------------------------ */

    public interface MessageListener {
        void onMessage(String type, String sourceServer, UUID uuid);
    }

    public void broadcastUpdate(UUID uuid) {
        try (Jedis jedis = pool.getResource()) {
            jedis.publish(CHANNEL_UPDATES, "SAVE:" + serverId + ":" + uuid);
        } catch (Exception ignored) {
        }
    }

    /** Asks the network to persist a player now (used by remote /invsee). */
    public void requestSave(UUID uuid) {
        try (Jedis jedis = pool.getResource()) {
            jedis.publish(CHANNEL_UPDATES, "REQ_SAVE:" + serverId + ":" + uuid);
        } catch (Exception ignored) {
        }
    }

    public void registerMessageListener(MessageListener listener) {
        closeSubscriber();
        subscriber = new UpdateSubscriber(listener);
        Runnable work = () -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = pool.getResource()) {
                    // subscribe() blocks until unsubscribe; reconnects on failure
                    jedis.subscribe(subscriber, CHANNEL_UPDATES);
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    plugin.getLogger().warning("PubSub disconnected, retrying in 5s: " + e.getMessage());
                    try {
                        Thread.sleep(5000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        };
        subscriberThread = new Thread(work, "AstralisSync-PubSub");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    private final class UpdateSubscriber extends redis.clients.jedis.JedisPubSub {
        private final MessageListener listener;

        private UpdateSubscriber(MessageListener listener) {
            this.listener = listener;
        }

        @Override
        public void onMessage(String channel, String message) {
            String[] parts = message.split(":", 3);
            if (parts.length != 3) {
                return;
            }
            String type = parts[0];
            String source = parts[1];
            UUID uuid;
            try {
                uuid = UUID.fromString(parts[2]);
            } catch (IllegalArgumentException e) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> listener.onMessage(type, source, uuid));
        }
    }

    public boolean isOwner(UUID uuid) {
        try (Jedis jedis = pool.getResource()) {
            return serverId.equals(jedis.get(KEY_LOCK + uuid));
        } catch (Exception e) {
            return false;
        }
    }

    public void reload() {
        close();
        connect();
    }

    public boolean isConnected() {
        return pool != null && !pool.isClosed();
    }

    public void close() {
        closeSubscriber();
        if (pool != null && !pool.isClosed()) {
            pool.close();
        }
    }

    private void closeSubscriber() {
        if (subscriber != null) {
            try {
                subscriber.unsubscribe();
            } catch (Exception ignored) {
            }
            subscriber = null;
        }
        if (subscriberThread != null) {
            subscriberThread.interrupt();
            subscriberThread = null;
        }
    }
}
