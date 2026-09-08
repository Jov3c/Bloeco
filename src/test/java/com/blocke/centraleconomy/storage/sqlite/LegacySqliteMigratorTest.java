package com.blocke.centraleconomy.storage.sqlite;

import com.blocke.centraleconomy.domain.account.AccountId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacySqliteMigratorTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC);

    @TempDir Path temporaryDirectory;

    @Test
    void migratesLegacyBalancesIntoBalancedJournals() throws Exception {
        Path database = createLegacyDatabase(false);
        LegacySqliteMigrator migrator = new LegacySqliteMigrator(CLOCK);

        MigrationReport report = migrator.migrateIfRequired(database);

        assertTrue(report.migrated());
        assertEquals(2, report.migratedEntries());
        assertTrue(Files.isRegularFile(report.backupPath()));
        try (SqliteLedgerStore migrated = new SqliteLedgerStore(database)) {
            assertEquals(7_500L, migrated.balance(AccountId.treasury()));
            assertEquals(2_500L, migrated.balance(AccountId.player(PLAYER)));
            assertEquals(10_000L, migrated.monetaryTotals().netSupplyMinor());
            assertTrue(migrated.verifyIntegrity().valid());
        }
    }

    @Test
    void failedValidationLeavesOriginalAndBackupUntouched() throws Exception {
        Path database = createLegacyDatabase(true);
        byte[] before = Files.readAllBytes(database);
        LegacySqliteMigrator migrator = new LegacySqliteMigrator(CLOCK);

        assertThrows(MigrationException.class, () -> migrator.migrateIfRequired(database));

        assertArrayEquals(before, Files.readAllBytes(database));
        assertTrue(Files.isRegularFile(migrator.lastBackupPath()));
    }

    private Path createLegacyDatabase(boolean inconsistentBalance) throws Exception {
        Path database = temporaryDirectory.resolve(inconsistentBalance ? "broken.db" : "economy.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE accounts (
                        account_id TEXT PRIMARY KEY,
                        balance_cents INTEGER NOT NULL CHECK(balance_cents >= 0)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE ledger_entries (
                        id TEXT PRIMARY KEY,
                        created_at_epoch_ms INTEGER,
                        transaction_type TEXT,
                        debit_account TEXT,
                        credit_account TEXT,
                        amount_cents INTEGER,
                        memo TEXT
                    )
                    """);
            try (PreparedStatement account = connection.prepareStatement(
                    "INSERT INTO accounts(account_id, balance_cents) VALUES (?, ?)")) {
                account.setString(1, "TREASURY");
                account.setLong(2, 7_500L);
                account.executeUpdate();
                account.setString(1, "PLAYER:" + PLAYER);
                account.setLong(2, inconsistentBalance ? 2_499L : 2_500L);
                account.executeUpdate();
            }
            try (PreparedStatement entry = connection.prepareStatement("""
                    INSERT INTO ledger_entries(id, created_at_epoch_ms, transaction_type,
                        debit_account, credit_account, amount_cents, memo)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert(entry, UUID.randomUUID(), 1_000L, "ISSUE", "ISSUANCE", "TREASURY", 10_000L, "launch");
                insert(entry, UUID.randomUUID(), 2_000L, "TREASURY_ALLOCATION", "TREASURY",
                        "PLAYER:" + PLAYER, 2_500L, "starter funds");
            }
        }
        return database;
    }

    private static void insert(PreparedStatement statement, UUID id, long createdAt, String type,
                               String debit, String credit, long amount, String memo) throws Exception {
        statement.setString(1, id.toString());
        statement.setLong(2, createdAt);
        statement.setString(3, type);
        statement.setString(4, debit);
        statement.setString(5, credit);
        statement.setLong(6, amount);
        statement.setString(7, memo);
        statement.executeUpdate();
    }
}
