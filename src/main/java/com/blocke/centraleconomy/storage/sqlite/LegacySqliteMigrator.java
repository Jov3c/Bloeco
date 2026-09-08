package com.blocke.centraleconomy.storage.sqlite;

import com.blocke.centraleconomy.domain.account.Account;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.ledger.JournalType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Converts the legacy balance table into a verified central journal using a staging database. */
public final class LegacySqliteMigrator {
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final Clock clock;
    private Path lastBackupPath;

    public LegacySqliteMigrator() {
        this(Clock.systemUTC());
    }

    LegacySqliteMigrator(Clock clock) {
        this.clock = clock;
    }

    public MigrationReport migrateIfRequired(Path databasePath) {
        Path database = databasePath.toAbsolutePath();
        if (!Files.exists(database) || !isLegacy(database)) {
            return new MigrationReport(false, null, 0);
        }

        String timestamp = BACKUP_TIME.format(clock.instant());
        lastBackupPath = database.resolveSibling(database.getFileName() + ".legacy-backup-" + timestamp);
        Path staging = database.resolveSibling(database.getFileName() + ".migration-" + timestamp + ".tmp");
        try {
            Files.deleteIfExists(lastBackupPath);
            Files.deleteIfExists(staging);
            createConsistentBackup(database, lastBackupPath);
            Files.copy(lastBackupPath, staging, StandardCopyOption.COPY_ATTRIBUTES);
            int migratedEntries = migrateStaging(staging);
            verifyStaging(staging);
            replaceAtomically(staging, database);
            return new MigrationReport(true, lastBackupPath, migratedEntries);
        } catch (Exception exception) {
            try { Files.deleteIfExists(staging); } catch (IOException ignored) { }
            if (exception instanceof MigrationException migrationException) {
                throw migrationException;
            }
            throw new MigrationException("legacy SQLite migration failed; original database was preserved", exception);
        }
    }

    public Path lastBackupPath() {
        return lastBackupPath;
    }

    private boolean isLegacy(Path database) {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery("PRAGMA table_info(accounts)")) {
            boolean balanceCents = false;
            boolean accountClass = false;
            while (columns.next()) {
                balanceCents |= "balance_cents".equalsIgnoreCase(columns.getString("name"));
                accountClass |= "account_class".equalsIgnoreCase(columns.getString("name"));
            }
            return balanceCents && !accountClass;
        } catch (SQLException exception) {
            throw new MigrationException("unable to inspect SQLite schema", exception);
        }
    }

    private void createConsistentBackup(Path database, Path backup) throws SQLException {
        String escaped = backup.toString().replace("'", "''");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("VACUUM INTO '" + escaped + "'");
        }
    }

    private int migrateStaging(Path staging) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + staging);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=OFF");
            statement.execute("BEGIN IMMEDIATE");
            try {
                statement.executeUpdate("ALTER TABLE accounts RENAME TO legacy_accounts");
                statement.executeUpdate("ALTER TABLE ledger_entries RENAME TO legacy_ledger_entries");
                SqliteSchema.apply(connection);
                seedAccounts(connection);
                int count = convertEntries(connection);
                rebuildBalances(connection);
                validateBalances(connection);
                statement.execute("COMMIT");
                return count;
            } catch (SQLException | RuntimeException exception) {
                try { statement.execute("ROLLBACK"); } catch (SQLException ignored) { }
                throw exception;
            }
        }
    }

    private void seedAccounts(Connection connection) throws SQLException {
        Set<String> legacyIds = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet accounts = statement.executeQuery("SELECT account_id FROM legacy_accounts")) {
            while (accounts.next()) legacyIds.add(accounts.getString(1));
        }
        try (Statement statement = connection.createStatement();
             ResultSet entries = statement.executeQuery(
                     "SELECT debit_account, credit_account FROM legacy_ledger_entries")) {
            while (entries.next()) {
                legacyIds.add(entries.getString(1));
                legacyIds.add(entries.getString(2));
            }
        }

        insertAccount(connection, Account.issuanceControl());
        insertAccount(connection, Account.retiredControl());
        insertAccount(connection, Account.treasury());
        insertAccount(connection, Account.taxRevenue());
        insertAccount(connection, Account.feeRevenue());
        for (String legacyId : legacyIds) {
            if (legacyId != null && legacyId.startsWith("PLAYER:")) {
                try {
                    insertAccount(connection, Account.player(UUID.fromString(legacyId.substring("PLAYER:".length()))));
                } catch (IllegalArgumentException exception) {
                    throw new MigrationException("legacy player account has invalid UUID: " + legacyId, exception);
                }
            } else {
                mapLegacyAccount(legacyId);
            }
        }
    }

    private void insertAccount(Connection connection, Account account) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT OR IGNORE INTO accounts(account_id, account_class, owner_type, owner_id, purpose,
                    status, permits_negative, parent_account_id, created_at_epoch_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, account.id().value());
            insert.setString(2, account.accountClass().name());
            insert.setString(3, account.ownerType().name());
            insert.setString(4, account.ownerId());
            insert.setString(5, account.purpose());
            insert.setString(6, account.status().name());
            insert.setInt(7, account.permitsNegativeBalance() ? 1 : 0);
            insert.setString(8, account.parentId() == null ? null : account.parentId().value());
            insert.setLong(9, clock.millis());
            insert.executeUpdate();
        }
        try (PreparedStatement balance = connection.prepareStatement(
                "INSERT OR IGNORE INTO account_balances(account_id, balance_minor) VALUES (?, 0)")) {
            balance.setString(1, account.id().value());
            balance.executeUpdate();
        }
    }

    private int convertEntries(Connection connection) throws SQLException {
        int count = 0;
        try (Statement query = connection.createStatement();
             ResultSet legacy = query.executeQuery("SELECT * FROM legacy_ledger_entries ORDER BY created_at_epoch_ms, id");
             PreparedStatement journal = connection.prepareStatement("""
                     INSERT INTO journal_entries(entry_id, journal_type, memo, client_id, idempotency_key,
                         reversal_of_entry_id, created_at_epoch_ms) VALUES (?, ?, ?, NULL, NULL, NULL, ?)
                     """);
             PreparedStatement posting = connection.prepareStatement(
                     "INSERT INTO postings(entry_id, line_no, account_id, amount_minor) VALUES (?, ?, ?, ?)") ) {
            while (legacy.next()) {
                String legacyId = legacy.getString("id");
                String entryId = normalizedEntryId(legacyId).toString();
                long amount = legacy.getLong("amount_cents");
                if (amount <= 0) throw new MigrationException("legacy entry has non-positive amount: " + legacyId);
                String debit = legacy.getString("debit_account");
                String credit = legacy.getString("credit_account");
                String memo = legacy.getString("memo");
                journal.setString(1, entryId);
                journal.setString(2, mapJournalType(legacy.getString("transaction_type"), debit, credit).name());
                journal.setString(3, memo == null || memo.isBlank() ? "Legacy entry " + legacyId : memo);
                journal.setLong(4, legacy.getLong("created_at_epoch_ms"));
                journal.executeUpdate();

                insertPosting(posting, entryId, 1, mapLegacyAccount(debit).value(), -amount);
                insertPosting(posting, entryId, 2, mapLegacyAccount(credit).value(), amount);
                count++;
            }
        }
        return count;
    }

    private static void insertPosting(PreparedStatement posting, String entryId, int line,
                                      String accountId, long amount) throws SQLException {
        posting.setString(1, entryId);
        posting.setInt(2, line);
        posting.setString(3, accountId);
        posting.setLong(4, amount);
        posting.executeUpdate();
    }

    private void rebuildBalances(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE account_balances
                    SET balance_minor = COALESCE((
                        SELECT SUM(p.amount_minor) FROM postings p
                        WHERE p.account_id = account_balances.account_id
                    ), 0)
                    """);
        }
    }

    private void validateBalances(Connection connection) throws SQLException {
        Map<AccountId, Long> expected = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet legacy = statement.executeQuery("SELECT account_id, balance_cents FROM legacy_accounts")) {
            while (legacy.next()) {
                AccountId mapped = mapLegacyAccount(legacy.getString(1));
                try {
                    expected.merge(mapped, legacy.getLong(2), Math::addExact);
                } catch (ArithmeticException exception) {
                    throw new MigrationException("legacy balances exceed the supported range", exception);
                }
            }
        }
        try (PreparedStatement actual = connection.prepareStatement(
                "SELECT balance_minor FROM account_balances WHERE account_id = ?")) {
            for (Map.Entry<AccountId, Long> entry : expected.entrySet()) {
                actual.setString(1, entry.getKey().value());
                try (ResultSet row = actual.executeQuery()) {
                    if (!row.next() || row.getLong(1) != entry.getValue()) {
                        throw new MigrationException("legacy balance mismatch for " + entry.getKey().value());
                    }
                }
            }
        }
    }

    private void verifyStaging(Path staging) {
        try (SqliteLedgerStore store = new SqliteLedgerStore(staging)) {
            if (!store.verifyIntegrity().valid()) {
                throw new MigrationException("migrated journal failed integrity verification");
            }
            store.monetaryTotals();
        }
    }

    private static void replaceAtomically(Path staging, Path database) throws IOException {
        try {
            Files.move(staging, database, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new MigrationException("filesystem does not support atomic database replacement", exception);
        }
    }

    private static AccountId mapLegacyAccount(String legacyId) {
        if (legacyId == null) throw new MigrationException("legacy entry has a null account");
        return switch (legacyId) {
            case "TREASURY" -> AccountId.treasury();
            case "ISSUANCE", "EXTERNAL_CREDIT" -> AccountId.issuanceControl();
            case "BURN", "EXTERNAL_DEBIT" -> AccountId.retiredControl();
            default -> {
                if (legacyId.startsWith("PLAYER:")) {
                    try {
                        yield AccountId.player(UUID.fromString(legacyId.substring("PLAYER:".length())));
                    } catch (IllegalArgumentException exception) {
                        throw new MigrationException("legacy player account has invalid UUID: " + legacyId, exception);
                    }
                }
                throw new MigrationException("unknown legacy account: " + legacyId);
            }
        };
    }

    private static JournalType mapJournalType(String legacyType, String debit, String credit) {
        if ("EXTERNAL_CREDIT".equals(debit) || "EXTERNAL_DEBIT".equals(credit)
                || (legacyType != null && (legacyType.startsWith("VAULT_") || legacyType.startsWith("EXTERNAL_")))) {
            return JournalType.LEGACY_EXTERNAL;
        }
        return switch (legacyType == null ? "" : legacyType) {
            case "ISSUE" -> JournalType.ISSUE;
            case "BURN" -> JournalType.RETIRE;
            case "TREASURY_ALLOCATION" -> JournalType.TREASURY_ALLOCATION;
            case "PLAYER_TRANSFER", "TRANSFER", "TRANSFER_FEE", "PERSONAL_INCOME_TAX" -> JournalType.PLAYER_TRANSFER;
            default -> throw new MigrationException("unknown legacy transaction type: " + legacyType);
        };
    }

    private static UUID normalizedEntryId(String legacyId) {
        try {
            return UUID.fromString(legacyId);
        } catch (IllegalArgumentException exception) {
            return UUID.nameUUIDFromBytes(("bloeco-legacy:" + legacyId).getBytes(StandardCharsets.UTF_8));
        }
    }
}
