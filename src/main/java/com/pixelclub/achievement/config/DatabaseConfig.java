package com.pixelclub.achievement.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Cross-server MySQL database configuration.
 * Prioritizes environment variables for all credentials and connection endpoints.
 * Stored optionally at config/pxc-achievements/database.json.
 */
public class DatabaseConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("PxcAchievements-DBConfig");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @SerializedName("_notice")
    private String notice = "Credentials must be provided via environment variables (PXC_MYSQL_URL or PXC_DB_HOST, PXC_DB_PORT, PXC_DB_NAME, PXC_DB_USER, PXC_DB_PASS).";

    @SerializedName("enabled")
    private boolean enabled = false;

    @SerializedName("host")
    private String host = "localhost";

    @SerializedName("port")
    private int port = 3306;

    @SerializedName("database")
    private String database = "pixelclub";

    @SerializedName("user")
    private String user = "";

    @SerializedName("password")
    private String password = "";

    @SerializedName("jdbc_url")
    private String jdbcUrl = "";

    @SerializedName("maximum_pool_size")
    private int maximumPoolSize = 8;

    @SerializedName("connection_timeout_ms")
    private long connectionTimeoutMs = 5000;

    public static DatabaseConfig loadOrCreate(Path file) {
        DatabaseConfig config = new DatabaseConfig();
        if (Files.exists(file)) {
            try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
                DatabaseConfig loaded = GSON.fromJson(reader, DatabaseConfig.class);
                if (loaded != null) {
                    config = loaded;
                    LOGGER.info("Loaded database configuration from {}", file);
                    return config;
                }
            } catch (Exception e) {
                LOGGER.error("Failed to read database configuration from {}, using env-first defaults", file, e);
            }
        }

        // Save template file if missing
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (OutputStreamWriter writer = new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
                GSON.toJson(config, writer);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to create database config template at {}", file, e);
        }

        return config;
    }

    /**
     * Determines whether MySQL cross-server mode is active.
     * Active if explicitly enabled in config or if DB env vars are defined.
     */
    public boolean isEnabled() {
        if (enabled) return true;
        return getEnv("PXC_MYSQL_URL") != null
                || getEnv("PXC_DB_HOST") != null
                || getEnv("PXC_MYSQL_HOST") != null;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEffectiveJdbcUrl() {
        String envUrl = getEnv("PXC_MYSQL_URL");
        if (envUrl != null && !envUrl.isEmpty()) {
            return envUrl;
        }

        if (this.jdbcUrl != null && !this.jdbcUrl.trim().isEmpty()) {
            return this.jdbcUrl.trim();
        }

        String effectiveHost = getEffectiveHost();
        int effectivePort = getEffectivePort();
        String effectiveDb = getEffectiveDatabase();

        return String.format("jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8mb4&serverTimezone=UTC&connectTimeout=5000&socketTimeout=10000",
                effectiveHost, effectivePort, effectiveDb);
    }

    public String getEffectiveHost() {
        String env = getEnv("PXC_DB_HOST", "PXC_MYSQL_HOST");
        return env != null && !env.isEmpty() ? env : host;
    }

    public int getEffectivePort() {
        String env = getEnv("PXC_DB_PORT", "PXC_MYSQL_PORT");
        if (env != null) {
            try {
                return Integer.parseInt(env);
            } catch (NumberFormatException ignored) {}
        }
        return port > 0 ? port : 3306;
    }

    public String getEffectiveDatabase() {
        String env = getEnv("PXC_DB_NAME", "PXC_MYSQL_DATABASE");
        return env != null && !env.isEmpty() ? env : database;
    }

    public String getEffectiveUser() {
        String env = getEnv("PXC_MYSQL_USER", "PXC_DB_USER");
        return env != null && !env.isEmpty() ? env : user;
    }

    public String getEffectivePassword() {
        String env = getEnv("PXC_MYSQL_PASSWORD", "PXC_DB_PASS");
        return env != null ? env : password;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize > 0 ? maximumPoolSize : 8;
    }

    public long getConnectionTimeoutMs() {
        return connectionTimeoutMs > 0 ? connectionTimeoutMs : 5000;
    }

    private static String getEnv(String... keys) {
        for (String k : keys) {
            String val = System.getenv(k);
            if (val != null && !val.trim().isEmpty()) {
                return val.trim();
            }
        }
        return null;
    }
}
