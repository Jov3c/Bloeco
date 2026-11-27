package com.blocke.centraleconomy;

import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageConfigTest {
    @Test
    void newInstallsDefaultToSqliteAndMysqlSupportsDatabaseCreation() {
        try (InputStream input = StorageConfigTest.class.getResourceAsStream("/config.yml")) {
            String config = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(config.contains("  type: sqlite"));
            assertTrue(config.contains("  lending-enabled: false"));
            assertTrue(config.lines().anyMatch(line -> line.stripLeading().startsWith("jdbc-url:")
                    && line.contains("createDatabaseIfNotExist=true")));
            assertFalse(org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.StringReader(config)).getBoolean("redis.enabled"));
        } catch (Exception exception) {
            throw new AssertionError("default config could not be read", exception);
        }
    }

    @Test
    void legacyClusterConfigPreservesRedisUnlessExplicitlyDisabled() {
        var config = new org.bukkit.configuration.file.YamlConfiguration();
        config.addDefault("redis.enabled", false);
        config.set("storage.type", "mysql");
        config.set("cluster.enabled", true);
        config.set("redis.uri", "redis://127.0.0.1:6379/0");
        assertTrue(CentralEconomyPlugin.redisEnabled(config));
        config.set("redis.enabled", false);
        assertFalse(CentralEconomyPlugin.redisEnabled(config));
    }

    @Test
    void mysqlJdbcUrlUsesAJavaSupportedCharacterEncoding() {
        try (InputStream input = StorageConfigTest.class.getResourceAsStream("/config.yml")) {
            String config = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(config.contains("characterEncoding=utf8mb4"));
            assertTrue(config.contains("characterEncoding=UTF-8"));
        } catch (Exception exception) {
            throw new AssertionError("default config could not be read", exception);
        }
    }
}
