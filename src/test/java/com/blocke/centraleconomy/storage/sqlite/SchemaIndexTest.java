package com.blocke.centraleconomy.storage.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaIndexTest {
    @Test
    void createsQueryIndexesIdempotently(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("indexes.db");
        try (SqliteLedgerStore ignored = new SqliteLedgerStore(path);
             var connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath())) {
            Set<String> indexes = new HashSet<>();
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='index'")) {
                while (rows.next()) indexes.add(rows.getString(1));
            }
            assertTrue(indexes.contains("ix_postings_account_entry"));
            assertTrue(indexes.contains("ix_journal_created"));
            assertTrue(indexes.contains("ix_journal_type_created"));
            assertTrue(indexes.contains("ix_tax_category_effective"));
            assertTrue(indexes.contains("ix_audit_created"));
            assertTrue(indexes.contains("ix_loan_payments_loan_paid"));
        }
        try (SqliteLedgerStore ignored = new SqliteLedgerStore(path)) {
            // A second initialization must remain a no-op migration.
        }
    }
}
