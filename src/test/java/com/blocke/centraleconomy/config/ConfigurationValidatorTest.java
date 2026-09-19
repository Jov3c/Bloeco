package com.blocke.centraleconomy.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationValidatorTest {
    @Test
    void rejectsInvalidProductionConfiguration() {
        ConfigurationSnapshot snapshot = new ConfigurationSnapshot(
                "mysql", "", "", true, "", 1, 0,
                0, -1, 0, -1, 10001, 0, -1, 10001, -1, 10_001,
                0, 0, 31);

        List<String> errors = ConfigurationValidator.validate(snapshot);

        assertTrue(errors.stream().anyMatch(error -> error.contains("MySQL")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("Redis")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("资金")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("基点")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("贷款期限")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("完整性")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("重连")));
    }

    @Test
    void rejectsUnknownStorageType() {
        ConfigurationSnapshot snapshot = new ConfigurationSnapshot(
                "postgres", "", "", false, "", 4, 60,
                1_000_000, 100, 250_000, 0, 0, 0, 100, 320, 2_000, 10_000,
                7, 15, 10);

        assertTrue(ConfigurationValidator.validate(snapshot).stream()
                .anyMatch(error -> error.contains("storage.type")));
    }

    @Test
    void allowsSqliteWithRedisDisabled() {
        ConfigurationSnapshot snapshot = new ConfigurationSnapshot(
                "sqlite", "", "", false, "", 4, 60,
                1_000_000, 100, 250_000, 100, 320, 100, 100, 320, 2_000, 10_000,
                7, 15, 10);

        assertTrue(ConfigurationValidator.validate(snapshot).isEmpty());
    }
}
