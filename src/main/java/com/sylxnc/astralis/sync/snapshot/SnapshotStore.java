package com.sylxnc.astralis.sync.snapshot;

import org.bukkit.plugin.Plugin;

import java.io.Closeable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/** MySQL storage for the rolling player snapshot history. */
public final class SnapshotStore implements Closeable {

    public record Entry(long id, UUID uuid, String name, String cause, String serverId, byte[] payload) {}

    private final Plugin plugin;
    private final javax.sql.DataSource source;

    public SnapshotStore(Plugin plugin, javax.sql.DataSource source) {
        this.plugin = plugin;
        this.source = source;
    }

    public void init() throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS player_snapshots (
                    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    uuid        CHAR(36)     NOT NULL,
                    name        VARCHAR(16)  NOT NULL,
                    cause       VARCHAR(32)  NOT NULL,
                    server_id   VARCHAR(64)  NULL,
                    data        LONGBLOB     NOT NULL,
                    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_snapshots_uuid_time (uuid, created_at DESC)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
                """;
        try (Connection conn = source.getConnection(); Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    public void insert(UUID uuid, String name, String cause, String serverId, byte[] payload) {
        String sql = "INSERT INTO player_snapshots (uuid, name, cause, server_id, data) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name != null ? name : "?");
            ps.setString(3, cause);
            ps.setString(4, serverId);
            ps.setBytes(5, payload);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "snapshot insert failed for " + uuid, e);
        }
    }

    /** Newest-first page of snapshot metadata (without payloads). */
    public List<Entry> list(UUID uuid, int limit, int offset) {
        String sql = "SELECT id, name, cause, server_id, created_at FROM player_snapshots WHERE uuid = ? ORDER BY id DESC LIMIT ? OFFSET ?";
        List<Entry> result = new ArrayList<>();
        try (Connection conn = source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, limit);
            ps.setInt(3, Math.max(offset, 0));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Entry(rs.getLong("id"), uuid, rs.getString("name"),
                            rs.getString("cause"), rs.getString("server_id"), null));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "snapshot list failed for " + uuid, e);
        }
        return result;
    }

    /** Loads a full payload including ownership check. */
    public Entry loadPayload(UUID uuid, long id) {
        String sql = "SELECT id, name, cause, server_id, data FROM player_snapshots WHERE id = ? AND uuid = ?";
        try (Connection conn = source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Entry(rs.getLong("id"), uuid, rs.getString("name"),
                            rs.getString("cause"), rs.getString("server_id"), rs.getBytes("data"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "snapshot load failed #" + id, e);
        }
        return null;
    }

    public int count(UUID uuid) {
        String sql = "SELECT COUNT(*) AS c FROM player_snapshots WHERE uuid = ?";
        try (Connection conn = source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("c") : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    /** Deletes all but the newest keep snapshots of the player. */
    public void prune(UUID uuid, int keep) {
        String sql = """
                DELETE FROM player_snapshots
                WHERE uuid = ? AND id NOT IN (
                    SELECT id FROM (
                        SELECT id FROM player_snapshots WHERE uuid = ? ORDER BY id DESC LIMIT ?
                    ) newest
                )""";
        try (Connection conn = source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, uuid.toString());
            ps.setInt(3, Math.max(keep, 0));
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "snapshot prune failed for " + uuid, e);
        }
    }

    @Override
    public void close() {
        // connection lifecycle is owned by DatabaseManager's pool
    }
}
