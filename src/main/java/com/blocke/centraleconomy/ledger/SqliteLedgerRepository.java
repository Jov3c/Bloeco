package com.blocke.centraleconomy.ledger;

import com.blocke.centraleconomy.money.Money;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** SQLite-backed ledger whose balances and entries change in one SQL transaction. */
public final class SqliteLedgerRepository implements LedgerRepository {
    private final Connection connection;
    private final Clock clock;
    private boolean transactionActive;

    public SqliteLedgerRepository(Path databasePath) {
        this(databasePath, Clock.systemUTC());
    }

    SqliteLedgerRepository(Path databasePath, Clock clock) {
        Objects.requireNonNull(databasePath, "databasePath");
        this.clock = Objects.requireNonNull(clock, "clock");
        try {
            Path parent = databasePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
            configureConnection();
            createSchema();
        } catch (SQLException | IOException exception) {
            throw new IllegalStateException("unable to open SQLite ledger", exception);
        }
    }

    @Override
    public synchronized void transfer(
            AccountId debitAccount,
            AccountId creditAccount,
            Money amount,
            TransactionType transactionType,
            String memo) {
        transferBatch(List.of(new Posting(debitAccount, creditAccount, amount, transactionType, memo)));
    }

    /** Issues new money from the special ISSUANCE debit account. */
    public synchronized void credit(AccountId creditAccount, Money amount, TransactionType transactionType, String memo) {
        transfer(AccountId.issuance(), creditAccount, amount, transactionType, memo);
    }

    @Override
    public synchronized void transferBatch(List<Posting> postings) {
        List<Posting> batch = List.copyOf(Objects.requireNonNull(postings, "postings"));
        if (batch.isEmpty()) {
            return;
        }

        inTransaction(transaction -> {
            transaction.applyLedgerPostings(batch);
            return null;
        });
    }

    /**
     * Runs a caller-owned SQLite transaction together with validated ledger postings.
     * Callers must use {@link SqlTransaction#applyLedgerPostings(List)} for every monetary mutation.
     */
    public synchronized <T> T inTransaction(SqlWork<T> work) {
        Objects.requireNonNull(work, "work");
        if (transactionActive) {
            throw new IllegalStateException("nested SQLite ledger transactions are not supported");
        }

        boolean transactionStarted = false;
        try {
            beginImmediate();
            transactionStarted = true;
            transactionActive = true;
            T result = work.run(new SqlTransaction());
            commit();
            return result;
        } catch (SQLException exception) {
            if (transactionStarted) {
                rollback();
            }
            throw new IllegalStateException("ledger transaction failed", exception);
        } catch (RuntimeException exception) {
            if (transactionStarted) {
                rollback();
            }
            throw exception;
        } finally {
            transactionActive = false;
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(SqlTransaction transaction) throws SQLException;
    }

    /** A ledger-owned SQLite transaction for related application rows and validated ledger postings. */
    public final class SqlTransaction {
        private SqlTransaction() {
        }

        public Connection connection() {
            return connection;
        }

        public void applyLedgerPostings(List<Posting> postings) {
            List<Posting> batch = List.copyOf(Objects.requireNonNull(postings, "postings"));
            if (batch.isEmpty()) {
                return;
            }
            try {
                Map<AccountId, Long> newBalances = verifiedNewBalances(aggregateOrdinaryAccountDeltas(batch));
                persistBalances(newBalances);
                insertEntries(batch);
            } catch (SQLException exception) {
                throw new IllegalStateException("ledger transaction failed", exception);
            }
        }
    }

    @Override
    public synchronized Money balance(AccountId accountId) {
        Objects.requireNonNull(accountId, "accountId");
        try {
            if (accountId.isIssuance()) {
                return Money.ofCents(issuedCents());
            }
            return Money.ofCents(storedBalance(accountId));
        } catch (SQLException exception) {
            throw new IllegalStateException("unable to read account balance", exception);
        }
    }

    /** Intended for diagnostics and integration tests; ledger rows remain the source of truth. */
    public synchronized long entryCount() {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM ledger_entries")) {
            return result.getLong(1);
        } catch (SQLException exception) {
            throw new IllegalStateException("unable to count ledger entries", exception);
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException exception) {
            throw new IllegalStateException("unable to close SQLite ledger", exception);
        }
    }

    private void configureConnection() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
    }

    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS accounts (
                        account_id TEXT PRIMARY KEY,
                        balance_cents INTEGER NOT NULL CHECK(balance_cents >= 0)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS ledger_entries (
                        id TEXT PRIMARY KEY,
                        created_at_epoch_ms INTEGER,
                        transaction_type TEXT,
                        debit_account TEXT,
                        credit_account TEXT,
                        amount_cents INTEGER CHECK(amount_cents > 0),
                        memo TEXT
                    )
                    """);
        }
    }

    private Map<AccountId, Long> aggregateOrdinaryAccountDeltas(List<Posting> batch) {
        Map<AccountId, Long> deltas = new HashMap<>();
        for (Posting posting : batch) {
            if (!posting.debitAccount().isUnfundedSource()) {
                addDelta(deltas, posting.debitAccount(), -posting.amount().cents());
            }
            addDelta(deltas, posting.creditAccount(), posting.amount().cents());
        }
        return deltas;
    }

    private static void addDelta(Map<AccountId, Long> deltas, AccountId accountId, long delta) {
        try {
            deltas.merge(accountId, delta, Math::addExact);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("batch amount exceeds supported range", exception);
        }
    }

    private Map<AccountId, Long> verifiedNewBalances(Map<AccountId, Long> deltas) throws SQLException {
        Map<AccountId, Long> balances = new HashMap<>();
        for (Map.Entry<AccountId, Long> delta : deltas.entrySet()) {
            long current = storedBalance(delta.getKey());
            long updated;
            try {
                updated = Math.addExact(current, delta.getValue());
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("account balance exceeds supported range", exception);
            }
            if (updated < 0) {
                throw new IllegalStateException("insufficient balance for " + delta.getKey().value());
            }
            balances.put(delta.getKey(), updated);
        }
        return balances;
    }

    private long storedBalance(AccountId accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance_cents FROM accounts WHERE account_id = ?")) {
            statement.setString(1, accountId.value());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        }
    }

    private long issuedCents() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(SUM(amount_cents), 0) FROM ledger_entries WHERE debit_account = ?")) {
            statement.setString(1, AccountId.issuance().value());
            try (ResultSet result = statement.executeQuery()) {
                return result.getLong(1);
            }
        }
    }

    private void persistBalances(Map<AccountId, Long> balances) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO accounts(account_id, balance_cents) VALUES (?, ?)
                ON CONFLICT(account_id) DO UPDATE SET balance_cents = excluded.balance_cents
                """)) {
            for (Map.Entry<AccountId, Long> balance : balances.entrySet()) {
                statement.setString(1, balance.getKey().value());
                statement.setLong(2, balance.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertEntries(List<Posting> batch) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ledger_entries(
                    id, created_at_epoch_ms, transaction_type, debit_account, credit_account, amount_cents, memo
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (Posting posting : batch) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setLong(2, clock.millis());
                statement.setString(3, posting.transactionType().name());
                statement.setString(4, posting.debitAccount().value());
                statement.setString(5, posting.creditAccount().value());
                statement.setLong(6, posting.amount().cents());
                statement.setString(7, posting.memo());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void beginImmediate() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
        }
    }

    private void commit() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("COMMIT");
        }
    }

    private void rollback() {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ROLLBACK");
        } catch (SQLException ignored) {
            // The original exception is the useful failure to report.
        }
    }
}
