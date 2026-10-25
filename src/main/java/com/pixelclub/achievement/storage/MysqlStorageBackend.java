package com.pixelclub.achievement.storage;

import com.pixelclub.achievement.config.DatabaseConfig;
import com.pixelclub.achievement.model.ClaimState;
import com.pixelclub.achievement.model.PlayerProgress;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-server MySQL persistence backend using HikariCP.
 * Provides monotonic batch upserts and atomic conditional claim updates.
 */
public class MysqlStorageBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger("PxcAchievements-MySQL");

    private final DatabaseConfig config;
    private HikariDataSource dataSource;
    private volatile boolean available = false;

    public MysqlStorageBackend(DatabaseConfig config) {
        this.config = config;
    }

    public synchronized boolean init() {
        if (!config.isEnabled()) {
            LOGGER.info("MySQL cross-server storage is not enabled; using local JSON file storage.");
            return false;
        }

        try {
            HikariConfig hikari = new HikariConfig();
            hikari.setPoolName("PxcAchievementsPool");
            hikari.setJdbcUrl(config.getEffectiveJdbcUrl());
            hikari.setUsername(config.getEffectiveUser());
            hikari.setPassword(config.getEffectivePassword());
            hikari.setMaximumPoolSize(config.getMaximumPoolSize());
            hikari.setConnectionTimeout(config.getConnectionTimeoutMs());
            hikari.setIdleTimeout(60000);
            hikari.setMaxLifetime(1800000);

            // Attempt MariaDB driver first, fallback to MySQL driver
            try {
                Class.forName("org.mariadb.jdbc.Driver");
                hikari.setDriverClassName("org.mariadb.jdbc.Driver");
            } catch (ClassNotFoundException e) {
                try {
                    Class.forName("com.mysql.cj.jdbc.Driver");
                    hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
                } catch (ClassNotFoundException ignored) {}
            }

            this.dataSource = new HikariDataSource(hikari);

            // Auto-migrate table
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pxc_achievements_progress (
                        player_uuid CHAR(36) NOT NULL,
                        achievement_id VARCHAR(128) NOT NULL,
                        progress INT NOT NULL DEFAULT 0,
                        state TINYINT NOT NULL DEFAULT 0,
                        updated_at BIGINT NOT NULL DEFAULT 0,
                        PRIMARY KEY (player_uuid, achievement_id),
                        INDEX idx_player_uuid (player_uuid)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """);
            }

            this.available = true;
            LOGGER.info("MySQL cross-server achievement storage connected successfully to {}.", config.getEffectiveHost());
            return true;
        } catch (Exception e) {
            LOGGER.warn("Failed to initialize MySQL storage backend (falling back to JSON): {}", e.getMessage());
            this.available = false;
            if (this.dataSource != null) {
                try {
                    this.dataSource.close();
                } catch (Exception ignored) {}
                this.dataSource = null;
            }
            return false;
        }
    }

    public boolean isAvailable() {
        return available && dataSource != null && !dataSource.isClosed();
    }

    public Connection getConnection() throws SQLException {
        if (!isAvailable()) {
            throw new SQLException("MySQL storage backend is not available");
        }
        return dataSource.getConnection();
    }

    /**
     * Loads fresh progress records for player upon join.
     */
    public ConcurrentHashMap<String, PlayerProgress> loadPlayer(UUID uuid) {
        ConcurrentHashMap<String, PlayerProgress> map = new ConcurrentHashMap<>();
        if (!isAvailable() || uuid == null) return map;

        String sql = "SELECT achievement_id, progress, state, updated_at FROM pxc_achievements_progress WHERE player_uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String achId = rs.getString("achievement_id");
                    int progress = rs.getInt("progress");
                    int stateId = rs.getInt("state");
                    long updatedAt = rs.getLong("updated_at");
                    map.put(achId, new PlayerProgress(progress, ClaimState.fromId(stateId), updatedAt));
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load achievement progress from MySQL for player {}: {}", uuid, e.getMessage());
        }
        return map;
    }

    /**
     * Monotonic batch upsert for dirty achievement progress.
     */
    public void saveBatch(UUID uuid, Map<String, PlayerProgress> updates) {
        if (!isAvailable() || uuid == null || updates == null || updates.isEmpty()) return;

        String sql = """
            INSERT INTO pxc_achievements_progress (player_uuid, achievement_id, progress, state, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              progress = GREATEST(progress, VALUES(progress)),
              state = GREATEST(state, VALUES(state)),
              updated_at = GREATEST(updated_at, VALUES(updated_at));
        """;

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            String uuidStr = uuid.toString();
            for (Map.Entry<String, PlayerProgress> entry : updates.entrySet()) {
                String achId = entry.getKey();
                PlayerProgress p = entry.getValue();
                ps.setString(1, uuidStr);
                ps.setString(2, achId);
                ps.setInt(3, p.getProgress());
                ps.setInt(4, p.getState().getId());
                ps.setLong(5, p.getTimestamp());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) {
            LOGGER.error("Failed to batch save achievement updates to MySQL for player {}: {}", uuid, e.getMessage());
        }
    }

    /**
     * Admin override: explicitly overwrites progress and state without GREATEST guard.
     */
    public void saveExplicit(UUID uuid, String achievementId, int progress, int state) {
        if (!isAvailable() || uuid == null || achievementId == null) return;

        String sql = """
            INSERT INTO pxc_achievements_progress (player_uuid, achievement_id, progress, state, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              progress = VALUES(progress),
              state = VALUES(state),
              updated_at = VALUES(updated_at);
        """;

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, achievementId);
            ps.setInt(3, progress);
            ps.setInt(4, state);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) {
            LOGGER.error("Failed to execute explicit achievement save in MySQL for player {} ({}): {}", uuid, achievementId, e.getMessage());
        }
    }

    /**
     * Admin reset single achievement: deletes row from MySQL.
     */
    public void deleteAchievement(UUID uuid, String achievementId) {
        if (!isAvailable() || uuid == null || achievementId == null) return;

        String sql = "DELETE FROM pxc_achievements_progress WHERE player_uuid = ? AND achievement_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, achievementId);
            ps.executeUpdate();
        } catch (Exception e) {
            LOGGER.error("Failed to delete achievement in MySQL for player {} ({}): {}", uuid, achievementId, e.getMessage());
        }
    }

    /**
     * Admin reset all achievements: deletes all rows for player from MySQL.
     */
    public void deleteAll(UUID uuid) {
        if (!isAvailable() || uuid == null) return;

        String sql = "DELETE FROM pxc_achievements_progress WHERE player_uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (Exception e) {
            LOGGER.error("Failed to delete all achievements in MySQL for player {}: {}", uuid, e.getMessage());
        }
    }

    /**
     * Atomic cross-server conditional claim guard.
     * Ensures row is in state=1 (COMPLETED), then atomically transitions to state=2 (CLAIMED).
     * Returns true ONLY if affectedRows == 1.
     */
    public boolean claimAtomic(UUID uuid, String achievementId, int currentProgress, long updatedAt) {
        if (!isAvailable() || uuid == null || achievementId == null) return false;

        String uuidStr = uuid.toString();

        // 1. Ensure the row exists in MySQL with state >= 1
        String ensureSql = """
            INSERT INTO pxc_achievements_progress (player_uuid, achievement_id, progress, state, updated_at)
            VALUES (?, ?, ?, 1, ?)
            ON DUPLICATE KEY UPDATE
              progress = GREATEST(progress, VALUES(progress)),
              state = GREATEST(state, VALUES(state)),
              updated_at = GREATEST(updated_at, VALUES(updated_at));
        """;

        String updateSql = """
            UPDATE pxc_achievements_progress
            SET state = 2, updated_at = ?
            WHERE player_uuid = ? AND achievement_id = ? AND state = 1;
        """;

        try (Connection conn = getConnection()) {
            try (PreparedStatement psEnsure = conn.prepareStatement(ensureSql)) {
                psEnsure.setString(1, uuidStr);
                psEnsure.setString(2, achievementId);
                psEnsure.setInt(3, currentProgress);
                psEnsure.setLong(4, updatedAt);
                psEnsure.executeUpdate();
            }

            try (PreparedStatement psUpdate = conn.prepareStatement(updateSql)) {
                psUpdate.setLong(1, updatedAt);
                psUpdate.setString(2, uuidStr);
                psUpdate.setString(3, achievementId);
                int affectedRows = psUpdate.executeUpdate();
                return affectedRows == 1;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to execute atomic claim in MySQL for player {} ({}): {}", uuid, achievementId, e.getMessage());
            return false;
        }
    }

    public synchronized void close() {
        this.available = false;
        if (this.dataSource != null) {
            try {
                this.dataSource.close();
                LOGGER.info("Closed MySQL storage connection pool.");
            } catch (Exception e) {
                LOGGER.error("Error closing MySQL pool: {}", e.getMessage());
            }
            this.dataSource = null;
        }
    }
}
