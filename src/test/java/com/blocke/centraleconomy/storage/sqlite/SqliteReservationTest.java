package com.blocke.centraleconomy.storage.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqliteReservationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void schemaAddsReservedBalanceWithZeroDefault() throws Exception {
        Path database = temporaryDirectory.resolve("economy.db");
        try (SqliteEcosystemStore ignored = new SqliteEcosystemStore(database)) { }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             var rows = connection.createStatement().executeQuery(
                     "SELECT dflt_value FROM pragma_table_info('account_balances') WHERE name='reserved_minor'")) {
            rows.next();
            assertEquals("0", rows.getString(1));
        }
    }
}
