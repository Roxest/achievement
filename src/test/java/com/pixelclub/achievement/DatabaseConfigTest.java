package com.pixelclub.achievement;

import com.pixelclub.achievement.config.DatabaseConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

public class DatabaseConfigTest {

    private Path tempDir;

    @BeforeEach
    public void setup() throws Exception {
        tempDir = Files.createTempDirectory("pxc_db_test_");
    }

    @AfterEach
    public void cleanup() throws Exception {
        if (Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    public void testDefaultConfig() {
        Path file = tempDir.resolve("database.json");
        DatabaseConfig config = DatabaseConfig.loadOrCreate(file);

        assertTrue(Files.exists(file), "Default database.json template must be created");
        assertEquals(8, config.getMaximumPoolSize());
        assertEquals(5000, config.getConnectionTimeoutMs());
        assertEquals(3306, config.getEffectivePort());
        assertNotNull(config.getEffectiveJdbcUrl());
        assertTrue(config.getEffectiveJdbcUrl().startsWith("jdbc:mysql://"));
    }

    @Test
    public void testExplicitEnable() {
        Path file = tempDir.resolve("database.json");
        DatabaseConfig config = DatabaseConfig.loadOrCreate(file);

        assertFalse(config.isEnabled(), "Should be disabled by default if no env vars");
        config.setEnabled(true);
        assertTrue(config.isEnabled());
    }
}
