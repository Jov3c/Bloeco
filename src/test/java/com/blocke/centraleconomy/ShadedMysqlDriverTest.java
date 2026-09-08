package com.blocke.centraleconomy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadedMysqlDriverTest {
    @Test
    void shadedJarRegistersMysqlJdbcDriver() throws Exception {
        Path jar = Path.of(System.getProperty("bloeco.shadow.jar"));
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("META-INF/services/java.sql.Driver");
            assertTrue(entry != null, "shaded JAR must contain the JDBC driver service file");
            String providers = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(providers.contains("com.mysql.cj.jdbc.Driver"),
                    "shaded JAR must register the MySQL JDBC driver");
        }
    }
}
