package com.blocke.centraleconomy;

import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageConfigTest {
    @Test
    void productionDefaultsUseMysqlAuthorityAndRedisAccelerator() {
        try (InputStream input = StorageConfigTest.class.getResourceAsStream("/config.yml")) {
            String config = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(config.contains("  type: mysql"));
            assertTrue(config.contains("  enabled: true"));
        } catch (Exception exception) {
            throw new AssertionError("default config could not be read", exception);
        }
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
