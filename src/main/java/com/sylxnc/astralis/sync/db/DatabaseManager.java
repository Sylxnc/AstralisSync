package com.sylxnc.astralis.sync.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.Closeable;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Level;

/**
 * MySQL persistence layer. Stores one binary snapshot blob per player plus
 * metadata (last server, timestamps). Uses HikariCP for pooling.
 */
public final class DatabaseManager implements Closeable {

    private final Plugin plugin;
    private volatile HikariDataSource dataSource;
    private String databaseName;

    public DatabaseManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean connect() {
        try {
            this.dataSource = buildSource();
            createTable();
            migrateSchema();
            plugin.getLogger().info("Connected to MySQL.");
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "MySQL connection failed", e);
            return false;
        }
    }

    private HikariDataSource buildSource() {
        FileConfiguration cfg = plugin.getConfig();
        databaseName = cfg.getString("mysql.database", "astralissync");

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=%s&allowPublicKeyRetrieval=true&autoReconnect=true&useUnicode=true&characterEncoding=utf8",
                cfg.getString("mysql.host", "127.0.0.1"),
                cfg.getInt("mysql.port", 3306),
                databaseName,
                cfg.getBoolean("mysql.use-ssl", false)));
        hikari.setUsername(cfg.getString("mysql.username", "root"));
        hikari.setPassword(cfg.getString("mysql.password", ""));
        hikari.setMaximumPoolSize(cfg.getInt("mysql.pool-size", 10));
        hikari.setMinimumIdle(2);
        hikari.setMaxLifetime(1800000);
        hikari.setConnectionTimeout(5000);
        hikari.setPoolName("AstralisSync-Pool");
        return new HikariDataSource(hikari);
    }

    /**
     * Non-destructive schema migration: adds the checksum column to player_data
     * and creates the quarantine table for corrupt rows.
     */
    private void migrateSchema() {
        String checksumSql = "ALTER TABLE player_data ADD COLUMN IF NOT EXISTS checksum CHAR(64) NULL";
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            try {
                st.execute(checksumSql);
            } catch (SQLException e) {
                if (isUnknownColumnError(e)) {
                    // Older servers do not support IF NOT EXISTS here - plain ALTER,
                    // duplicate-column errors are expected and ignored.
                    addChecksumColumnPlain();
                } else if (!isDuplicateColumnError(e)) {
                    throw e;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to add player_data.checksum column", e);
        }
        String quarantineSql = """
                CREATE TABLE IF NOT EXISTS corrupted_player_data (
                    id          BIGINT    NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    uuid        CHAR(36)  NOT NULL,
                    data        LONGBLOB  NOT NULL,
                    checksum    CHAR(64)  NULL,
                    detected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
                """;
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute(quarantineSql);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to create corrupted_player_data table", e);
        }
    }

    private void addChecksumColumnPlain() {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE player_data ADD COLUMN checksum CHAR(64) NULL");
            plugin.getLogger().info("Added player_data.checksum column.");
        } catch (SQLException e) {
            if (!isDuplicateColumnError(e)) {
                plugin.getLogger().log(Level.SEVERE, "Failed to add player_data.checksum column", e);
            }
        }
    }

    private static boolean isDuplicateColumnError(SQLException e) {
        return e.getErrorCode() == 1060 || containsIgnoreCase(e.getMessage(), "duplicate column");
    }

    private static boolean isUnknownColumnError(SQLException e) {
        return e.getErrorCode() == 1054 || containsIgnoreCase(e.getMessage(), "unknown column");
    }

    private static boolean containsIgnoreCase(String message, String needle) {
        return message != null && message.toLowerCase().contains(needle);
    }

    private void createTable() throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS player_data (
                    uuid         CHAR(36)    NOT NULL PRIMARY KEY,
                    last_name    VARCHAR(16) NOT NULL,
                    data         LONGBLOB    NOT NULL,
                    last_server  VARCHAR(64) NULL,
                    data_version INT         NOT NULL DEFAULT 0,
                    updated_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
                """;
        String metaSql = """
                CREATE TABLE IF NOT EXISTS player_meta (
                    uuid             CHAR(36)   NOT NULL PRIMARY KEY,
                    enderchest_rows  INT        NOT NULL DEFAULT 3
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
                """;
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute(sql);
            st.execute(metaSql);
        }
    }

    /** Resolves the last known UUID for a name (from stored player data). */
    public UUID lookupUuid(String name) {
        String sql = "SELECT uuid FROM player_data WHERE last_name = ? ORDER BY updated_at DESC LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? UUID.fromString(rs.getString("uuid")) : null;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "lookupUuid failed for " + name, e);
            return null;
        }
    }

    public int getEnderChestRows(UUID uuid) {        String sql = "SELECT enderchest_rows FROM player_meta WHERE uuid = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("enderchest_rows") : 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "getEnderChestRows failed for " + uuid, e);
            return 0;
        }
    }

    public void setEnderChestRows(UUID uuid, int rows) {
        String sql = """
                INSERT INTO player_meta (uuid, enderchest_rows) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE enderchest_rows = VALUES(enderchest_rows)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, rows);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "setEnderChestRows failed for " + uuid, e);
        }
    }

    /**
     * Returns the stored snapshot or null when the player is unknown or the row
     * failed the integrity check (corrupt rows are quarantined automatically).
     */
    public byte[] loadData(UUID uuid) {
        String sql = "SELECT data, checksum FROM player_data WHERE uuid = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                byte[] data = rs.getBytes("data");
                String storedChecksum = rs.getString("checksum");
                if (!isChecksumValid(uuid, data, storedChecksum)) {
                    quarantineCorruptRow(conn, uuid, data, storedChecksum);
                    deleteData(uuid);
                    return null;
                }
                return data;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "loadData failed for " + uuid, e);
            return null;
        }
    }

    public boolean saveData(UUID uuid, String name, byte[] payload, String serverId) {
        String sql;
        if (checksumsEnabled()) {
            sql = """
                    INSERT INTO player_data (uuid, last_name, data, checksum, last_server, data_version)
                    VALUES (?, ?, ?, ?, ?, 1)
                    ON DUPLICATE KEY UPDATE
                        last_name    = VALUES(last_name),
                        data         = VALUES(data),
                        checksum     = VALUES(checksum),
                        last_server  = VALUES(last_server),
                        data_version = data_version + 1
                    """;
        } else {
            sql = """
                    INSERT INTO player_data (uuid, last_name, data, last_server, data_version)
                    VALUES (?, ?, ?, ?, 1)
                    ON DUPLICATE KEY UPDATE
                        last_name    = VALUES(last_name),
                        data         = VALUES(data),
                        last_server  = VALUES(last_server),
                        data_version = data_version + 1
                    """;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name != null ? name : "?");
            ps.setBytes(3, payload);
            if (checksumsEnabled()) {
                ps.setString(4, sha256Hex(payload));
                ps.setString(5, serverId);
            } else {
                ps.setString(4, serverId);
            }
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "saveData failed for " + uuid, e);
            return false;
        }
    }

    private boolean checksumsEnabled() {
        return plugin.getConfig().getBoolean("integrity.checksums", true);
    }

    private static String sha256Hex(byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Null/empty stored checksum means a legacy row - accepted silently. */
    private boolean isChecksumValid(UUID uuid, byte[] data, String storedChecksum) {
        if (storedChecksum == null || storedChecksum.isEmpty()) {
            return true;
        }
        boolean valid = data != null && storedChecksum.equalsIgnoreCase(sha256Hex(data));
        if (!valid) {
            plugin.getLogger().log(Level.SEVERE, "Checksum mismatch for player " + uuid
                    + " - quarantining corrupt row");
        }
        return valid;
    }

    private void quarantineCorruptRow(Connection conn, UUID uuid, byte[] data, String checksum) {
        if (data == null) {
            return;
        }
        String sql = "INSERT INTO corrupted_player_data (uuid, data, checksum) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setBytes(2, data);
            ps.setString(3, checksum);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to quarantine corrupt row for " + uuid, e);
        }
    }

    /**
     * Loads a usable snapshot for the player: prefers the live row and falls
     * back to the newest quarantined copy whose bytes still match its checksum.
     * Intended for manual recovery by admins.
     */
    public byte[] loadAnyValid(UUID uuid) {
        byte[] current = loadData(uuid);
        if (current != null) {
            return current;
        }
        String sql = """
                SELECT data, checksum FROM corrupted_player_data WHERE uuid = ?
                ORDER BY detected_at DESC, id DESC LIMIT 10
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byte[] data = rs.getBytes("data");
                    String checksum = rs.getString("checksum");
                    if (data == null || checksum == null || checksum.isEmpty()) {
                        continue;
                    }
                    if (checksum.equalsIgnoreCase(sha256Hex(data))) {
                        plugin.getLogger().info("Recovered quarantined snapshot for " + uuid);
                        return data;
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "loadAnyValid failed for " + uuid, e);
        }
        return null;
    }

    /**
     * Verifies every stored checksum in batches. Returns false when at least
     * one row mismatches (each mismatch is logged). Legacy rows without a
     * checksum are ignored.
     */
    public boolean verifyAll() {
        final int batchSize = 100;
        int offset = 0;
        boolean allValid = true;
        String sql = "SELECT uuid, data, checksum FROM player_data ORDER BY uuid LIMIT 100 OFFSET ?";
        while (true) {
            int fetched;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    fetched = 0;
                    while (rs.next()) {
                        fetched++;
                        String uuidStr = rs.getString("uuid");
                        byte[] data = rs.getBytes("data");
                        String checksum = rs.getString("checksum");
                        if (checksum == null || checksum.isEmpty()) {
                            continue;
                        }
                        boolean ok = data != null && checksum.equalsIgnoreCase(sha256Hex(data));
                        if (!ok) {
                            allValid = false;
                            plugin.getLogger().severe("verifyAll: checksum mismatch for player "
                                    + uuidStr);
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "verifyAll failed at offset " + offset, e);
                return false;
            }
            if (fetched < batchSize) {
                break;
            }
            offset += batchSize;
        }
        return allValid;
    }

    public boolean deleteData(UUID uuid) {
        String sql = "DELETE FROM player_data WHERE uuid = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "deleteData failed for " + uuid, e);
            return false;
        }
    }

    public void reload() {
        close();
        connect();
    }

    public boolean isConnected() {
        return dataSource != null && !dataSource.isClosed();
    }

    public javax.sql.DataSource getSource() {
        return dataSource;
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
