package com.blocke.centraleconomy.storage.sqlite;

import com.blocke.centraleconomy.application.IntegrityReport;
import com.blocke.centraleconomy.application.IssuanceRecord;
import com.blocke.centraleconomy.application.LedgerStore;
import com.blocke.centraleconomy.application.MonetaryTotals;
import com.blocke.centraleconomy.domain.account.Account;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.ledger.JournalEntry;
import com.blocke.centraleconomy.domain.ledger.JournalType;
import com.blocke.centraleconomy.domain.ledger.LedgerException;
import com.blocke.centraleconomy.domain.ledger.Posting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** SQLite authority for the immutable, balanced Bloeco central journal. */
public final class SqliteLedgerStore implements LedgerStore {
    private final Connection connection;
    private boolean closed;

    public SqliteLedgerStore(Path databasePath) {
        Objects.requireNonNull(databasePath, "databasePath");
        try {
            Path absolute = databasePath.toAbsolutePath();
            if (absolute.getParent() != null) {
                Files.createDirectories(absolute.getParent());
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            configure();
            SqliteSchema.apply(connection);
        } catch (SQLException | IOException exception) {
            throw storageFailure("unable to open SQLite ledger", exception);
        }
    }

    @Override
    public synchronized void createAccount(Account account) {
        requireOpen();
        Objects.requireNonNull(account, "account");
        try {
            beginImmediate();
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT OR IGNORE INTO accounts(
                        account_id, account_class, owner_type, owner_id, purpose, status,
                        permits_negative, parent_account_id, created_at_epoch_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, account.id().value());
                insert.setString(2, account.accountClass().name());
                insert.setString(3, account.ownerType().name());
                insert.setString(4, account.ownerId());
                insert.setString(5, account.purpose());
                insert.setString(6, account.status().name());
                insert.setInt(7, account.permitsNegativeBalance() ? 1 : 0);
                insert.setString(8, account.parentId() == null ? null : account.parentId().value());
                insert.setLong(9, System.currentTimeMillis());
                insert.executeUpdate();
            }
            try (PreparedStatement balance = connection.prepareStatement(
                    "INSERT OR IGNORE INTO account_balances(account_id, balance_minor) VALUES (?, 0)")) {
                balance.setString(1, account.id().value());
                balance.executeUpdate();
            }
            commitTransaction();
        } catch (SQLException | RuntimeException exception) {
            rollback();
            if (exception instanceof LedgerException ledgerException) throw ledgerException;
            throw storageFailure("unable to create account", exception);
        }
    }

    @Override
    public synchronized JournalEntry commit(JournalEntry entry) {
        requireOpen();
        Objects.requireNonNull(entry, "entry");
        if (entry.clientId() != null) {
            Optional<JournalEntry> existing = idempotentResult(entry.clientId(), entry.idempotencyKey());
            if (existing.isPresent()) {
                if (!sameRequest(existing.get(), entry)) {
                    throw new LedgerException(LedgerException.Code.IDEMPOTENCY_CONFLICT,
                            "idempotency key was already used for a different request");
                }
                return existing.get();
            }
        }

        try {
            beginImmediate();
            applyEntry(entry);
            commitTransaction();
            return entry;
        } catch (SQLException | RuntimeException exception) {
            rollback();
            if (exception instanceof LedgerException ledgerException) throw ledgerException;
            throw storageFailure("unable to commit journal", exception);
        }
    }

    @Override
    public synchronized void createIssuanceRequest(IssuanceRecord request) {
        requireOpen();
        Objects.requireNonNull(request, "request");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO issuance_requests(request_id, amount_minor, status, reason, requester_id,
                    approver_id, requested_at_epoch_ms, approved_at_epoch_ms, executed_entry_id)
                VALUES (?, ?, ?, ?, ?, NULL, ?, NULL, NULL)
                """)) {
            statement.setString(1, request.requestId().toString());
            statement.setLong(2, request.amount().minor());
            statement.setString(3, request.status().name());
            statement.setString(4, request.reason());
            statement.setString(5, request.requesterId());
            statement.setLong(6, request.requestedAt().toEpochMilli());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw storageFailure("unable to create issuance request", exception);
        }
    }

    @Override
    public synchronized IssuanceRecord issuanceRequest(UUID requestId) {
        requireOpen();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM issuance_requests WHERE request_id = ?")) {
            statement.setString(1, requestId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new LedgerException(LedgerException.Code.POLICY_REJECTED,
                        "issuance request does not exist");
                return readIssuance(result);
            }
        } catch (SQLException exception) {
            throw storageFailure("unable to read issuance request", exception);
        }
    }

    @Override
    public synchronized IssuanceRecord approveIssuance(UUID requestId, String approverId, Instant approvedAt) {
        requireOpen();
        try {
            beginImmediate();
            IssuanceRecord request = issuanceRequest(requestId);
            if (request.status() != IssuanceRecord.Status.REQUESTED) {
                throw new LedgerException(LedgerException.Code.POLICY_REJECTED,
                        "issuance request is not awaiting approval");
            }
            if (request.requesterId().equals(approverId)) {
                throw new LedgerException(LedgerException.Code.POLICY_REJECTED,
                        "issuance requester cannot approve the same request");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE issuance_requests SET status = 'APPROVED', approver_id = ?, approved_at_epoch_ms = ?
                    WHERE request_id = ? AND status = 'REQUESTED'
                    """)) {
                statement.setString(1, approverId);
                statement.setLong(2, approvedAt.toEpochMilli());
                statement.setString(3, requestId.toString());
                if (statement.executeUpdate() != 1) {
                    throw new LedgerException(LedgerException.Code.POLICY_REJECTED,
                            "issuance request changed during approval");
                }
            }
            commitTransaction();
            return issuanceRequest(requestId);
        } catch (SQLException | RuntimeException exception) {
            rollback();
            if (exception instanceof LedgerException ledgerException) throw ledgerException;
            throw storageFailure("unable to approve issuance request", exception);
        }
    }

    @Override
    public synchronized JournalEntry commitApprovedIssuance(UUID requestId, JournalEntry entry) {
        requireOpen();
        if (entry.type() != JournalType.ISSUE || entry.postings().size() != 2
                || !entry.postings().contains(new Posting(AccountId.issuanceControl(),
                -entry.postings().stream().filter(p -> p.accountId().equals(AccountId.treasury()))
                        .mapToLong(Posting::amountMinor).findFirst().orElse(0)))) {
            throw new LedgerException(LedgerException.Code.INVALID_JOURNAL,
                    "issuance must debit issuance control and credit Treasury");
        }
        if (entry.clientId() != null) {
            Optional<JournalEntry> replay = idempotentResult(entry.clientId(), entry.idempotencyKey());
            if (replay.isPresent()) {
                if (!sameRequest(replay.get(), entry)) throw new LedgerException(
                        LedgerException.Code.IDEMPOTENCY_CONFLICT, "idempotency key conflicts with existing issuance");
                return replay.get();
            }
        }
        try {
            beginImmediate();
            IssuanceRecord request = issuanceRequest(requestId);
            if (request.status() != IssuanceRecord.Status.APPROVED) {
                throw new LedgerException(LedgerException.Code.POLICY_REJECTED,
                        "issuance request is not approved");
            }
            long treasuryCredit = entry.postings().stream()
                    .filter(p -> p.accountId().equals(AccountId.treasury()))
                    .mapToLong(Posting::amountMinor).findFirst().orElseThrow();
            if (treasuryCredit != request.amount().minor()) {
                throw new LedgerException(LedgerException.Code.POLICY_REJECTED,
                        "issuance journal amount differs from approved request");
            }
            applyEntry(entry);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE issuance_requests SET status = 'EXECUTED', executed_entry_id = ?
                    WHERE request_id = ? AND status = 'APPROVED'
                    """)) {
                statement.setString(1, entry.id().toString());
                statement.setString(2, requestId.toString());
                if (statement.executeUpdate() != 1) throw new LedgerException(
                        LedgerException.Code.POLICY_REJECTED, "issuance request changed during execution");
            }
            commitTransaction();
            return entry;
        } catch (SQLException | RuntimeException exception) {
            rollback();
            if (exception instanceof LedgerException ledgerException) throw ledgerException;
            throw storageFailure("unable to execute issuance request", exception);
        }
    }

    @Override
    public synchronized void setPolicyLimit(String key, long value, String actorId, String memo, Instant changedAt) {
        requireOpen();
        try {
            beginImmediate();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO policy_limits(policy_key, value_minor, actor_id, memo, updated_at_epoch_ms)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(policy_key) DO UPDATE SET value_minor=excluded.value_minor,
                        actor_id=excluded.actor_id, memo=excluded.memo, updated_at_epoch_ms=excluded.updated_at_epoch_ms
                    """)) {
                statement.setString(1, key);
                statement.setLong(2, value);
                statement.setString(3, actorId);
                statement.setString(4, memo);
                statement.setLong(5, changedAt.toEpochMilli());
                statement.executeUpdate();
            }
            insertAudit(actorId, "POLICY_LIMIT_CHANGED", memo, null, changedAt);
            commitTransaction();
        } catch (SQLException | RuntimeException exception) {
            rollback();
            throw storageFailure("unable to update policy limit", exception);
        }
    }

    @Override
    public synchronized long policyLimit(String key, long defaultValue) {
        requireOpen();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT value_minor FROM policy_limits WHERE policy_key = ?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : defaultValue;
            }
        } catch (SQLException exception) {
            throw storageFailure("unable to read policy limit", exception);
        }
    }

    @Override
    public synchronized long issuedSince(Instant since) {
        return flowSince(JournalType.ISSUE, AccountId.treasury(), since);
    }

    @Override
    public synchronized long retiredSince(Instant since) {
        return flowSince(JournalType.RETIRE, AccountId.retiredControl(), since);
    }

    @Override
    public synchronized long balance(AccountId accountId) {
        requireOpen();
        Objects.requireNonNull(accountId, "accountId");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance_minor FROM account_balances WHERE account_id = ?")) {
            statement.setString(1, accountId.value());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new LedgerException(LedgerException.Code.ACCOUNT_NOT_FOUND,
                            "account does not exist: " + accountId.value());
                }
                return result.getLong(1);
            }
        } catch (SQLException exception) {
            throw storageFailure("unable to read balance", exception);
        }
    }

    @Override
    public synchronized Optional<JournalEntry> entry(UUID entryId) {
        requireOpen();
        Objects.requireNonNull(entryId, "entryId");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM journal_entries WHERE entry_id = ?")) {
            statement.setString(1, entryId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readEntry(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw storageFailure("unable to read journal", exception);
        }
    }

    @Override
    public synchronized Optional<JournalEntry> idempotentResult(String clientId, String idempotencyKey) {
        requireOpen();
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT j.* FROM idempotency_records i
                JOIN journal_entries j ON j.entry_id = i.entry_id
                WHERE i.client_id = ? AND i.idempotency_key = ?
                """)) {
            statement.setString(1, clientId);
            statement.setString(2, idempotencyKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readEntry(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw storageFailure("unable to read idempotency record", exception);
        }
    }

    @Override
    public synchronized boolean hasReversal(UUID originalEntryId) {
        requireOpen();
        Objects.requireNonNull(originalEntryId, "originalEntryId");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM journal_entries WHERE reversal_of_entry_id = ? LIMIT 1")) {
            statement.setString(1, originalEntryId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException exception) {
            throw storageFailure("unable to query journal reversal", exception);
        }
    }

    @Override
    public synchronized int entryCount() {
        return scalarInt("SELECT COUNT(*) FROM journal_entries");
    }

    @Override
    public synchronized int entriesForKey(String clientId, String idempotencyKey) {
        requireOpen();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM journal_entries WHERE client_id = ? AND idempotency_key = ?")) {
            statement.setString(1, clientId);
            statement.setString(2, idempotencyKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        } catch (SQLException exception) {
            throw storageFailure("unable to count idempotent journals", exception);
        }
    }

    @Override
    public synchronized MonetaryTotals monetaryTotals() {
        long issued = Math.negateExact(balance(AccountId.issuanceControl()));
        long retired = balance(AccountId.retiredControl());
        return new MonetaryTotals(issued, retired, Math.subtractExact(issued, retired));
    }

    @Override
    public synchronized IntegrityReport verifyIntegrity() {
        requireOpen();
        List<String> violations = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet journals = statement.executeQuery("""
                     SELECT entry_id, COUNT(*) AS line_count, SUM(amount_minor) AS line_sum
                     FROM postings GROUP BY entry_id
                     """)) {
            while (journals.next()) {
                if (journals.getInt("line_count") < 2 || journals.getLong("line_sum") != 0) {
                    violations.add("unbalanced journal " + journals.getString("entry_id"));
                }
            }
            Map<String, Long> materialized = new HashMap<>();
            try (ResultSet balances = statement.executeQuery(
                    "SELECT account_id, balance_minor FROM account_balances")) {
                while (balances.next()) materialized.put(balances.getString(1), balances.getLong(2));
            }
            Map<String, Long> rebuilt = new HashMap<>();
            try (ResultSet postings = statement.executeQuery(
                    "SELECT account_id, COALESCE(SUM(amount_minor), 0) FROM postings GROUP BY account_id")) {
                while (postings.next()) rebuilt.put(postings.getString(1), postings.getLong(2));
            }
            for (Map.Entry<String, Long> balance : materialized.entrySet()) {
                if (!Objects.equals(balance.getValue(), rebuilt.getOrDefault(balance.getKey(), 0L))) {
                    violations.add("balance mismatch " + balance.getKey());
                }
            }
            return violations.isEmpty() ? IntegrityReport.validReport() : new IntegrityReport(false, violations);
        } catch (SQLException exception) {
            throw storageFailure("unable to verify ledger integrity", exception);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        try {
            connection.close();
            closed = true;
        } catch (SQLException exception) {
            throw storageFailure("unable to close SQLite ledger", exception);
        }
    }

    private void configure() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA synchronous=FULL");
        }
    }

    private void applyEntry(JournalEntry entry) throws SQLException {
        Map<AccountId, Long> deltas = aggregate(entry.postings());
        Map<AccountId, StoredAccount> accounts = loadAccounts(deltas.keySet().stream().sorted().toList());
        Map<AccountId, Long> updatedBalances = new LinkedHashMap<>();
        for (Map.Entry<AccountId, Long> delta : deltas.entrySet()) {
            StoredAccount account = accounts.get(delta.getKey());
            if (account == null) {
                throw new LedgerException(LedgerException.Code.ACCOUNT_NOT_FOUND,
                        "account does not exist: " + delta.getKey().value());
            }
            if (!"ACTIVE".equals(account.status)) {
                throw new LedgerException(LedgerException.Code.ACCOUNT_FROZEN,
                        "account is not active: " + delta.getKey().value());
            }
            long updated;
            try {
                updated = Math.addExact(account.balance, delta.getValue());
            } catch (ArithmeticException exception) {
                throw new LedgerException(LedgerException.Code.INVALID_AMOUNT,
                        "account balance exceeds the supported range", exception);
            }
            if (!account.permitsNegative && updated < 0) {
                throw new LedgerException(LedgerException.Code.INSUFFICIENT_FUNDS,
                        "account has insufficient funds: " + delta.getKey().value());
            }
            updatedBalances.put(delta.getKey(), updated);
        }
        insertJournal(entry);
        updateBalances(updatedBalances);
        if (entry.clientId() != null) insertIdempotency(entry);
    }

    private IssuanceRecord readIssuance(ResultSet result) throws SQLException {
        String approver = result.getString("approver_id");
        long approvedAt = result.getLong("approved_at_epoch_ms");
        String executed = result.getString("executed_entry_id");
        return new IssuanceRecord(UUID.fromString(result.getString("request_id")),
                com.blocke.centraleconomy.domain.money.Money.ofMinor(result.getLong("amount_minor")),
                IssuanceRecord.Status.valueOf(result.getString("status")), result.getString("reason"),
                result.getString("requester_id"), approver,
                Instant.ofEpochMilli(result.getLong("requested_at_epoch_ms")),
                approver == null ? null : Instant.ofEpochMilli(approvedAt),
                executed == null ? null : UUID.fromString(executed));
    }

    private long flowSince(JournalType type, AccountId positiveAccount, Instant since) {
        requireOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(p.amount_minor), 0)
                FROM journal_entries j JOIN postings p ON p.entry_id = j.entry_id
                WHERE j.journal_type = ? AND p.account_id = ? AND p.amount_minor > 0
                    AND j.created_at_epoch_ms >= ?
                """)) {
            statement.setString(1, type.name());
            statement.setString(2, positiveAccount.value());
            statement.setLong(3, since.toEpochMilli());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        } catch (SQLException exception) {
            throw storageFailure("unable to query monetary flow", exception);
        }
    }

    private void insertAudit(String actorId, String action, String memo, UUID entryId, Instant at)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO audit_events(event_id, actor_id, action, memo, entry_id, created_at_epoch_ms)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, actorId);
            statement.setString(3, action);
            statement.setString(4, memo);
            statement.setString(5, entryId == null ? null : entryId.toString());
            statement.setLong(6, at.toEpochMilli());
            statement.executeUpdate();
        }
    }

    private Map<AccountId, Long> aggregate(List<Posting> postings) {
        Map<AccountId, Long> deltas = new LinkedHashMap<>();
        try {
            for (Posting posting : postings) {
                deltas.merge(posting.accountId(), posting.amountMinor(), Math::addExact);
            }
        } catch (ArithmeticException exception) {
            throw new LedgerException(LedgerException.Code.INVALID_AMOUNT,
                    "posting total exceeds the supported range", exception);
        }
        return deltas;
    }

    private Map<AccountId, StoredAccount> loadAccounts(List<AccountId> accountIds) throws SQLException {
        Map<AccountId, StoredAccount> result = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.status, a.permits_negative, b.balance_minor
                FROM accounts a JOIN account_balances b ON b.account_id = a.account_id
                WHERE a.account_id = ?
                """)) {
            for (AccountId accountId : accountIds) {
                statement.setString(1, accountId.value());
                try (ResultSet row = statement.executeQuery()) {
                    if (row.next()) {
                        result.put(accountId, new StoredAccount(row.getString(1), row.getInt(2) == 1, row.getLong(3)));
                    }
                }
            }
        }
        return result;
    }

    private void insertJournal(JournalEntry entry) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO journal_entries(entry_id, journal_type, memo, client_id, idempotency_key,
                    reversal_of_entry_id, created_at_epoch_ms) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, entry.id().toString());
            statement.setString(2, entry.type().name());
            statement.setString(3, entry.memo());
            statement.setString(4, entry.clientId());
            statement.setString(5, entry.idempotencyKey());
            statement.setString(6, entry.reversalOf() == null ? null : entry.reversalOf().toString());
            statement.setLong(7, entry.createdAt().toEpochMilli());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO postings(entry_id, line_no, account_id, amount_minor) VALUES (?, ?, ?, ?)")) {
            for (int index = 0; index < entry.postings().size(); index++) {
                Posting posting = entry.postings().get(index);
                statement.setString(1, entry.id().toString());
                statement.setInt(2, index + 1);
                statement.setString(3, posting.accountId().value());
                statement.setLong(4, posting.amountMinor());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void updateBalances(Map<AccountId, Long> balances) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE account_balances SET balance_minor = ? WHERE account_id = ?")) {
            for (Map.Entry<AccountId, Long> balance : balances.entrySet()) {
                statement.setLong(1, balance.getValue());
                statement.setString(2, balance.getKey().value());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertIdempotency(JournalEntry entry) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO idempotency_records(client_id, idempotency_key, entry_id, request_fingerprint)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setString(1, entry.clientId());
            statement.setString(2, entry.idempotencyKey());
            statement.setString(3, entry.id().toString());
            statement.setString(4, fingerprint(entry));
            statement.executeUpdate();
        }
    }

    private JournalEntry readEntry(ResultSet row) throws SQLException {
        UUID id = UUID.fromString(row.getString("entry_id"));
        List<Posting> postings = new ArrayList<>();
        try (PreparedStatement lines = connection.prepareStatement(
                "SELECT account_id, amount_minor FROM postings WHERE entry_id = ? ORDER BY line_no")) {
            lines.setString(1, id.toString());
            try (ResultSet result = lines.executeQuery()) {
                while (result.next()) {
                    postings.add(new Posting(new AccountId(result.getString(1)), result.getLong(2)));
                }
            }
        }
        String reversal = row.getString("reversal_of_entry_id");
        return new JournalEntry(id, JournalType.valueOf(row.getString("journal_type")), row.getString("memo"),
                row.getString("client_id"), row.getString("idempotency_key"),
                reversal == null ? null : UUID.fromString(reversal),
                Instant.ofEpochMilli(row.getLong("created_at_epoch_ms")), postings);
    }

    private static boolean sameRequest(JournalEntry first, JournalEntry second) {
        return first.type() == second.type()
                && first.memo().equals(second.memo())
                && Objects.equals(first.reversalOf(), second.reversalOf())
                && first.postings().equals(second.postings());
    }

    private static String fingerprint(JournalEntry entry) {
        return Integer.toHexString(Objects.hash(entry.type(), entry.memo(), entry.reversalOf(), entry.postings()));
    }

    private int scalarInt(String sql) {
        requireOpen();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getInt(1) : 0;
        } catch (SQLException exception) {
            throw storageFailure("unable to query SQLite ledger", exception);
        }
    }

    private void beginImmediate() throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute("BEGIN IMMEDIATE"); }
    }

    private void commitTransaction() throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute("COMMIT"); }
    }

    private void rollback() {
        try (Statement statement = connection.createStatement()) { statement.execute("ROLLBACK"); }
        catch (SQLException ignored) { }
    }

    private void requireOpen() {
        if (closed) {
            throw new LedgerException(LedgerException.Code.STORAGE_UNAVAILABLE, "ledger is closed");
        }
    }

    private static LedgerException storageFailure(String message, Exception cause) {
        return new LedgerException(LedgerException.Code.STORAGE_UNAVAILABLE, message, cause);
    }

    private record StoredAccount(String status, boolean permitsNegative, long balance) {}
}
