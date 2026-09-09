package com.blocke.centraleconomy.storage.sqlite;

import com.blocke.centraleconomy.application.banking.BankingStore;
import com.blocke.centraleconomy.domain.account.Account;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.banking.BankSnapshot;
import com.blocke.centraleconomy.domain.banking.BankingPlayerSnapshot;
import com.blocke.centraleconomy.domain.banking.BankingPolicy;
import com.blocke.centraleconomy.domain.banking.BankingReceipt;
import com.blocke.centraleconomy.domain.banking.LoanReceipt;
import com.blocke.centraleconomy.domain.ledger.JournalType;
import com.blocke.centraleconomy.domain.ledger.LedgerException;
import com.blocke.centraleconomy.domain.money.Money;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** SQLite-compatible banking backend used for development, imports, and tests. */
public class SqliteBankingStore implements BankingStore {
    private static final String BANK_ID = "bloeco.bank";
    private static final String CLIENT_ID = "bloeco-bank";
    private static final long DAY_MILLIS = 86_400_000L;
    private final Connection connection;
    private final Clock clock;
    private final boolean mysql;
    private final boolean closeConnection;
    private boolean closed;

    public SqliteBankingStore(Path database, Clock clock) {
        this(open(database), clock, false, true);
    }

    /** Internal JDBC session constructor shared by the pooled MySQL adapter. */
    public SqliteBankingStore(Connection connection, Clock clock, boolean mysql, boolean closeConnection) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.mysql = mysql;
        this.closeConnection = closeConnection;
        try {
            if (!mysql) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA foreign_keys = ON");
                    statement.execute("PRAGMA busy_timeout = 5000");
                }
                SqliteSchema.apply(connection);
            }
        } catch (SQLException exception) {
            throw storage("unable to open SQLite banking store", exception);
        }
    }

    @Override
    public synchronized void initialize(Money initialCapital, BankingPolicy defaults) {
        write(() -> {
            ensureAccount(Account.bankCash());
            long now = clock.millis();
            try (PreparedStatement insert = connection.prepareStatement(insertIgnore("""
                    INSERT INTO banks(bank_id, display_name, cash_account_id,
                        deposit_rate_bps, loan_rate_bps, reserve_ratio_bps, maximum_loan_minor,
                        lending_enabled, loan_term_days, created_at_epoch_ms, updated_at_epoch_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """))) {
                insert.setString(1, BANK_ID);
                insert.setString(2, "Bloeco 国有银行");
                insert.setString(3, AccountId.bankCash().value());
                insert.setInt(4, defaults.depositRateBasisPoints());
                insert.setInt(5, defaults.loanRateBasisPoints());
                insert.setInt(6, defaults.reserveRatioBasisPoints());
                insert.setLong(7, defaults.maximumLoan().minor());
                insert.setInt(8, defaults.lendingEnabled() ? 1 : 0);
                insert.setInt(9, defaults.loanTermDays());
                insert.setLong(10, now);
                insert.setLong(11, now);
                insert.executeUpdate();
            }
            if (operation("capital:v1") == null) {
                transfer(AccountId.treasury(), AccountId.bankCash(), initialCapital.minor());
                UUID journal = journal(JournalType.BANK_CAPITAL_INJECTION, "国库向 Bloeco 国有银行注入初始资本",
                        "capital:v1", AccountId.treasury(), AccountId.bankCash(), initialCapital.minor());
                operation("capital:v1", "CAPITAL", null, initialCapital.minor(), null, journal);
            }
            return null;
        });
    }

    @Override
    public synchronized BankingReceipt deposit(UUID playerId, Money amount, String key) {
        requirePositive(amount);
        return write(() -> {
            Operation replay = operation(key);
            if (replay != null) return replay.receipt("DEPOSIT", playerId, amount.minor());
            accrueDeposit(playerId);
            AccountId wallet = ensurePlayer(playerId);
            transfer(wallet, AccountId.bankCash(), amount.minor());
            try (PreparedStatement statement = connection.prepareStatement(mysql ? """
                    INSERT INTO bank_deposits(bank_id, player_uuid, principal_minor,
                        accrued_interest_minor, last_interest_epoch_ms, status, updated_at_epoch_ms)
                    VALUES (?, ?, ?, 0, ?, 'ACTIVE', ?)
                    ON DUPLICATE KEY UPDATE principal_minor = principal_minor + VALUES(principal_minor),
                        status = 'ACTIVE', updated_at_epoch_ms = VALUES(updated_at_epoch_ms)
                    """ : """
                    INSERT INTO bank_deposits(bank_id, player_uuid, principal_minor,
                        accrued_interest_minor, last_interest_epoch_ms, status, updated_at_epoch_ms)
                    VALUES (?, ?, ?, 0, ?, 'ACTIVE', ?)
                    ON CONFLICT(bank_id, player_uuid) DO UPDATE SET
                        principal_minor = principal_minor + excluded.principal_minor,
                        status = 'ACTIVE', updated_at_epoch_ms = excluded.updated_at_epoch_ms
                    """)) {
                statement.setString(1, BANK_ID);
                statement.setString(2, playerId.toString());
                statement.setLong(3, amount.minor());
                statement.setLong(4, clock.millis());
                statement.setLong(5, clock.millis());
                statement.executeUpdate();
            }
            UUID journal = journal(JournalType.BANK_DEPOSIT, "存入 Bloeco 国有银行",
                    key, wallet, AccountId.bankCash(), amount.minor());
            return operation(key, "DEPOSIT", playerId, amount.minor(), null, journal).receipt();
        });
    }

    @Override
    public synchronized BankingReceipt depositAll(UUID playerId, String key) {
        return write(() -> {
            Operation replay = operation(key);
            if (replay != null) return replay.receipt("DEPOSIT", playerId);
            accrueDeposit(playerId);
            AccountId wallet = ensurePlayer(playerId);
            long amount = balance(wallet);
            if (amount <= 0) throw insufficient("钱包中没有可存入的余额");
            transfer(wallet, AccountId.bankCash(), amount);
            try (PreparedStatement statement = connection.prepareStatement(mysql ? """
                    INSERT INTO bank_deposits(bank_id, player_uuid, principal_minor,
                        accrued_interest_minor, last_interest_epoch_ms, status, updated_at_epoch_ms)
                    VALUES (?, ?, ?, 0, ?, 'ACTIVE', ?)
                    ON DUPLICATE KEY UPDATE principal_minor = principal_minor + VALUES(principal_minor),
                        status = 'ACTIVE', updated_at_epoch_ms = VALUES(updated_at_epoch_ms)
                    """ : """
                    INSERT INTO bank_deposits(bank_id, player_uuid, principal_minor,
                        accrued_interest_minor, last_interest_epoch_ms, status, updated_at_epoch_ms)
                    VALUES (?, ?, ?, 0, ?, 'ACTIVE', ?)
                    ON CONFLICT(bank_id, player_uuid) DO UPDATE SET
                        principal_minor = principal_minor + excluded.principal_minor,
                        status = 'ACTIVE', updated_at_epoch_ms = excluded.updated_at_epoch_ms
                    """)) {
                statement.setString(1, BANK_ID);
                statement.setString(2, playerId.toString());
                statement.setLong(3, amount);
                statement.setLong(4, clock.millis());
                statement.setLong(5, clock.millis());
                statement.executeUpdate();
            }
            UUID journal = journal(JournalType.BANK_DEPOSIT, "全部存入 Bloeco 国有银行",
                    key, wallet, AccountId.bankCash(), amount);
            return operation(key, "DEPOSIT", playerId, amount, null, journal).receipt();
        });
    }

    @Override
    public synchronized BankingReceipt withdraw(UUID playerId, Money amount, String key) {
        requirePositive(amount);
        return write(() -> {
            Operation replay = operation(key);
            if (replay != null) return replay.receipt("WITHDRAW", playerId, amount.minor());
            accrueDeposit(playerId);
            long deposit = depositBalance(playerId);
            if (deposit < amount.minor()) throw insufficient("银行存款不足");
            BankingPolicy currentPolicy = policy();
            long allDeposits = scalar("SELECT COALESCE(SUM(principal_minor + accrued_interest_minor),0) FROM bank_deposits WHERE status='ACTIVE'");
            long cash = balance(AccountId.bankCash());
            long remainingDeposits = allDeposits - amount.minor();
            if (cash - amount.minor() < percentage(remainingDeposits, currentPolicy.reserveRatioBasisPoints())) {
                throw rejected("取款后将低于最低准备金率");
            }
            AccountId wallet = ensurePlayer(playerId);
            transfer(AccountId.bankCash(), wallet, amount.minor());
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE bank_deposits SET
                        principal_minor = CASE WHEN accrued_interest_minor >= ? THEN principal_minor
                            ELSE principal_minor - (? - accrued_interest_minor) END,
                        accrued_interest_minor = CASE WHEN accrued_interest_minor >= ?
                            THEN accrued_interest_minor - ? ELSE 0 END,
                        updated_at_epoch_ms = ?
                    WHERE bank_id = ? AND player_uuid = ?
                        AND principal_minor + accrued_interest_minor >= ?
                    """)) {
                statement.setLong(1, amount.minor());
                statement.setLong(2, amount.minor());
                statement.setLong(3, amount.minor());
                statement.setLong(4, amount.minor());
                statement.setLong(5, clock.millis());
                statement.setString(6, BANK_ID);
                statement.setString(7, playerId.toString());
                statement.setLong(8, amount.minor());
                if (statement.executeUpdate() != 1) throw insufficient("银行存款不足");
            }
            UUID journal = journal(JournalType.BANK_WITHDRAWAL, "从 Bloeco 国有银行取出存款",
                    key, AccountId.bankCash(), wallet, amount.minor());
            return operation(key, "WITHDRAW", playerId, amount.minor(), null, journal).receipt();
        });
    }

    @Override
    public synchronized BankingReceipt withdrawAll(UUID playerId, String key) {
        return write(() -> {
            Operation replay = operation(key);
            if (replay != null) return replay.receipt("WITHDRAW", playerId);
            accrueDeposit(playerId);
            long amount = depositBalance(playerId);
            if (amount <= 0) throw insufficient("银行中没有可取出的存款");
            BankingPolicy currentPolicy = policy();
            long allDeposits = scalar("SELECT COALESCE(SUM(principal_minor + accrued_interest_minor),0) FROM bank_deposits WHERE status='ACTIVE'");
            long cash = balance(AccountId.bankCash());
            long remainingDeposits = allDeposits - amount;
            if (cash - amount < percentage(remainingDeposits, currentPolicy.reserveRatioBasisPoints())) {
                throw rejected("取款后将低于最低准备金率");
            }
            AccountId wallet = ensurePlayer(playerId);
            transfer(AccountId.bankCash(), wallet, amount);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE bank_deposits SET principal_minor = 0, accrued_interest_minor = 0,
                        updated_at_epoch_ms = ?
                    WHERE bank_id = ? AND player_uuid = ?
                        AND principal_minor + accrued_interest_minor = ?
                    """)) {
                statement.setLong(1, clock.millis());
                statement.setString(2, BANK_ID);
                statement.setString(3, playerId.toString());
                statement.setLong(4, amount);
                if (statement.executeUpdate() != 1) throw insufficient("银行存款余额发生变化，请重试");
            }
            UUID journal = journal(JournalType.BANK_WITHDRAWAL, "从 Bloeco 国有银行取出全部存款",
                    key, AccountId.bankCash(), wallet, amount);
            return operation(key, "WITHDRAW", playerId, amount, null, journal).receipt();
        });
    }

    @Override
    public synchronized LoanReceipt borrow(UUID playerId, Money principal, String key) {
        requirePositive(principal);
        return write(() -> {
            Operation replay = operation(key);
            if (replay != null) {
                replay.validate("LOAN", playerId, principal.minor());
                LoanState existingLoan = loan(replay.loanId);
                long termDays = Math.max(1, ChronoUnit.DAYS.between(existingLoan.issuedAt, existingLoan.dueAt));
                long originalInterest = BankingPolicy.annualizedInterest(
                        Money.ofMinor(existingLoan.originalPrincipal),
                        existingLoan.interestRateBasisPoints, termDays).minor();
                return new LoanReceipt(replay.loanId, replay.journalId, Money.ofMinor(existingLoan.originalPrincipal),
                        Money.ofMinor(existingLoan.originalPrincipal + originalInterest), existingLoan.dueAt);
            }
            BankingPolicy policy = policy();
            if (!policy.lendingEnabled()) throw rejected("银行当前暂停放贷");
            long existing = playerDebt(playerId);
            if (Math.addExact(existing, principal.minor()) > policy.maximumLoan().minor()) {
                throw rejected("贷款额度不足");
            }
            long deposits = scalar("SELECT COALESCE(SUM(principal_minor + accrued_interest_minor),0) FROM bank_deposits WHERE status='ACTIVE'");
            long cash = balance(AccountId.bankCash());
            long reserve = percentage(deposits, policy.reserveRatioBasisPoints());
            if (cash - principal.minor() < reserve) throw rejected("银行准备金不足");
            AccountId wallet = ensurePlayer(playerId);
            transfer(AccountId.bankCash(), wallet, principal.minor());
            UUID journal = journal(JournalType.LOAN_DISBURSEMENT, "Bloeco 国有银行发放信用贷款",
                    key, AccountId.bankCash(), wallet, principal.minor());
            UUID loanId = UUID.randomUUID();
            long interest = policy.quotedInterest(principal).minor();
            Instant dueAt = clock.instant().plus(policy.loanTermDays(), ChronoUnit.DAYS);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO bank_loans(loan_id, bank_id, borrower_uuid, original_principal_minor,
                        outstanding_principal_minor, outstanding_interest_minor, interest_rate_bps,
                        issued_at_epoch_ms, due_at_epoch_ms, status, disbursement_journal_id, updated_at_epoch_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                    """)) {
                statement.setString(1, loanId.toString());
                statement.setString(2, BANK_ID);
                statement.setString(3, playerId.toString());
                statement.setLong(4, principal.minor());
                statement.setLong(5, principal.minor());
                statement.setLong(6, interest);
                statement.setInt(7, policy.loanRateBasisPoints());
                statement.setLong(8, clock.millis());
                statement.setLong(9, dueAt.toEpochMilli());
                statement.setString(10, journal.toString());
                statement.setLong(11, clock.millis());
                statement.executeUpdate();
            }
            operation(key, "LOAN", playerId, principal.minor(), loanId, journal);
            return new LoanReceipt(loanId, journal, principal, Money.ofMinor(principal.minor() + interest), dueAt);
        });
    }

    @Override
    public synchronized BankingReceipt repay(UUID playerId, UUID loanId, Money amount, String key) {
        requirePositive(amount);
        return write(() -> {
            Operation replay = operation(key);
            if (replay != null) return replay.receipt("REPAY", playerId, amount.minor());
            LoanState loan = loan(loanId);
            if (!loan.playerId.equals(playerId)) throw rejected("不能偿还其他玩家的贷款");
            long due = Math.addExact(loan.principal, loan.interest);
            if (due == 0 || amount.minor() > due) throw rejected("还款金额超过剩余应还金额");
            AccountId wallet = ensurePlayer(playerId);
            transfer(wallet, AccountId.bankCash(), amount.minor());
            long interestPaid = Math.min(amount.minor(), loan.interest);
            long principalPaid = amount.minor() - interestPaid;
            long remainingInterest = loan.interest - interestPaid;
            long remainingPrincipal = loan.principal - principalPaid;
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE bank_loans SET outstanding_principal_minor = ?, outstanding_interest_minor = ?,
                        status = ?, updated_at_epoch_ms = ? WHERE loan_id = ?
                    """)) {
                statement.setLong(1, remainingPrincipal);
                statement.setLong(2, remainingInterest);
                statement.setString(3, remainingPrincipal == 0 && remainingInterest == 0 ? "PAID" : "ACTIVE");
                statement.setLong(4, clock.millis());
                statement.setString(5, loanId.toString());
                statement.executeUpdate();
            }
            UUID journal = journal(JournalType.LOAN_REPAYMENT, "偿还 Bloeco 国有银行贷款",
                    key, wallet, AccountId.bankCash(), amount.minor());
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO bank_loan_payments(payment_id, loan_id, principal_minor,
                        interest_minor, journal_id, paid_at_epoch_ms) VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, loanId.toString());
                statement.setLong(3, principalPaid);
                statement.setLong(4, interestPaid);
                statement.setString(5, journal.toString());
                statement.setLong(6, clock.millis());
                statement.executeUpdate();
            }
            return operation(key, "REPAY", playerId, amount.minor(), loanId, journal).receipt();
        });
    }

    @Override
    public synchronized BankingPlayerSnapshot playerSnapshot(UUID playerId) {
        return write(() -> {
            accrueDeposit(playerId);
            AccountId wallet = AccountId.player(playerId);
            long walletBalance;
            try { walletBalance = balance(wallet); } catch (LedgerException exception) { walletBalance = 0; }
            long debt = playerDebt(playerId);
            BankingPolicy currentPolicy = policy();
            boolean overdue;
            UUID nextLoanId = null;
            Money nextLoanDue = Money.ofMinor(0);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT loan_id, due_at_epoch_ms, outstanding_principal_minor, outstanding_interest_minor
                    FROM bank_loans
                    WHERE borrower_uuid = ? AND status IN ('ACTIVE','OVERDUE')
                    ORDER BY due_at_epoch_ms, issued_at_epoch_ms LIMIT 1
                    """)) {
                statement.setString(1, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        nextLoanId = UUID.fromString(result.getString("loan_id"));
                        nextLoanDue = Money.ofMinor(Math.addExact(
                                result.getLong("outstanding_principal_minor"),
                                result.getLong("outstanding_interest_minor")));
                        overdue = result.getLong("due_at_epoch_ms") < clock.millis();
                    } else overdue = false;
                }
            }
            String grade = debt == 0 ? "A" : overdue ? "D"
                    : debt >= currentPolicy.maximumLoan().minor() * 8L / 10L ? "C" : "B";
            return new BankingPlayerSnapshot(Money.ofMinor(walletBalance), Money.ofMinor(depositBalance(playerId)),
                    Money.ofMinor(debt), overdue, grade,
                    nextLoanId, nextLoanDue);
        });
    }

    @Override
    public synchronized BankSnapshot bankSnapshot() {
        return read(() -> new BankSnapshot(Money.ofMinor(balance(AccountId.bankCash())),
                Money.ofMinor(scalar("SELECT COALESCE(SUM(principal_minor + accrued_interest_minor),0) FROM bank_deposits WHERE status='ACTIVE'")),
                Money.ofMinor(scalar("SELECT COALESCE(SUM(outstanding_principal_minor + outstanding_interest_minor),0) FROM bank_loans WHERE status IN ('ACTIVE','OVERDUE')")),
                scalar("SELECT COUNT(*) FROM bank_loans WHERE status='ACTIVE'"),
                scalar("SELECT COUNT(*) FROM bank_loans WHERE status='OVERDUE' OR (status='ACTIVE' AND due_at_epoch_ms < " + clock.millis() + ")"),
                policy()));
    }

    @Override
    public synchronized BankingPolicy updatePolicy(BankingPolicy policy, String actorId) {
        Objects.requireNonNull(actorId, "actorId");
        return write(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE banks SET deposit_rate_bps=?, loan_rate_bps=?, reserve_ratio_bps=?,
                        maximum_loan_minor=?, lending_enabled=?, loan_term_days=?, updated_at_epoch_ms=?
                    WHERE bank_id=?
                    """)) {
                statement.setInt(1, policy.depositRateBasisPoints());
                statement.setInt(2, policy.loanRateBasisPoints());
                statement.setInt(3, policy.reserveRatioBasisPoints());
                statement.setLong(4, policy.maximumLoan().minor());
                statement.setInt(5, policy.lendingEnabled() ? 1 : 0);
                statement.setInt(6, policy.loanTermDays());
                statement.setLong(7, clock.millis());
                statement.setString(8, BANK_ID);
                if (statement.executeUpdate() != 1) throw rejected("银行尚未初始化");
            }
            try (PreparedStatement audit = connection.prepareStatement("""
                    INSERT INTO audit_events(event_id,actor_id,action,memo,entry_id,created_at_epoch_ms)
                    VALUES (?,?,?,?,NULL,?)
                    """)) {
                audit.setString(1, UUID.randomUUID().toString());
                audit.setString(2, actorId);
                audit.setString(3, "BANK_POLICY_CHANGED");
                audit.setString(4, "更新 Bloeco 国有银行经营政策");
                audit.setLong(5, clock.millis());
                audit.executeUpdate();
            }
            return policy;
        });
    }

    private BankingPolicy policy() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM banks WHERE bank_id=?")) {
            statement.setString(1, BANK_ID);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw rejected("银行尚未初始化");
                return new BankingPolicy(result.getInt("deposit_rate_bps"), result.getInt("loan_rate_bps"),
                        result.getInt("reserve_ratio_bps"), Money.ofMinor(result.getLong("maximum_loan_minor")),
                        result.getInt("lending_enabled") == 1, result.getInt("loan_term_days"));
            }
        }
    }

    private AccountId ensurePlayer(UUID playerId) throws SQLException {
        Account account = Account.player(playerId);
        ensureAccount(account);
        return account.id();
    }

    private void ensureAccount(Account account) throws SQLException {
        String createdColumn = mysql ? "created_at" : "created_at_epoch_ms";
        try (PreparedStatement insert = connection.prepareStatement(insertIgnore("""
                INSERT INTO accounts(account_id, account_class, owner_type, owner_id, purpose,
                    status, permits_negative, parent_account_id, %s)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(createdColumn)))) {
            insert.setString(1, account.id().value());
            insert.setString(2, account.accountClass().name());
            insert.setString(3, account.ownerType().name());
            insert.setString(4, account.ownerId());
            insert.setString(5, account.purpose());
            insert.setString(6, account.status().name());
            insert.setInt(7, account.permitsNegativeBalance() ? 1 : 0);
            insert.setString(8, account.parentId() == null ? null : account.parentId().value());
            if (mysql) insert.setTimestamp(9, Timestamp.from(clock.instant()));
            else insert.setLong(9, clock.millis());
            insert.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(insertIgnore(
                "INSERT INTO account_balances(account_id,balance_minor) VALUES (?,0)"))) {
            insert.setString(1, account.id().value());
            insert.executeUpdate();
        }
    }

    private void transfer(AccountId source, AccountId destination, long amount) throws SQLException {
        try (PreparedStatement debit = connection.prepareStatement("""
                UPDATE account_balances SET balance_minor=balance_minor-?
                WHERE account_id=? AND balance_minor>=?
                """)) {
            debit.setLong(1, amount);
            debit.setString(2, source.value());
            debit.setLong(3, amount);
            if (debit.executeUpdate() != 1) throw insufficient("可用余额不足");
        }
        try (PreparedStatement credit = connection.prepareStatement(
                "UPDATE account_balances SET balance_minor=balance_minor+? WHERE account_id=?")) {
            credit.setLong(1, amount);
            credit.setString(2, destination.value());
            if (credit.executeUpdate() != 1) throw rejected("收款账户不存在");
        }
    }

    private UUID journal(JournalType type, String memo, String key,
                         AccountId source, AccountId destination, long amount) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO journal_entries(entry_id,journal_type,memo,client_id,idempotency_key,
                    reversal_of_entry_id,created_at_epoch_ms) VALUES (?,?,?,?,?,NULL,?)
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, type.name());
            statement.setString(3, memo);
            statement.setString(4, CLIENT_ID);
            statement.setString(5, key);
            statement.setLong(6, clock.millis());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO postings(entry_id,line_no,account_id,amount_minor) VALUES (?,?,?,?)")) {
            statement.setString(1, id.toString());
            statement.setInt(2, 1);
            statement.setString(3, source.value());
            statement.setLong(4, -amount);
            statement.addBatch();
            statement.setInt(2, 2);
            statement.setString(3, destination.value());
            statement.setLong(4, amount);
            statement.addBatch();
            statement.executeBatch();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO idempotency_records(client_id,idempotency_key,entry_id,request_fingerprint)
                VALUES (?,?,?,?)
                """)) {
            statement.setString(1, CLIENT_ID);
            statement.setString(2, key);
            statement.setString(3, id.toString());
            statement.setString(4, Integer.toHexString(Objects.hash(type, source, destination, amount)));
            statement.executeUpdate();
        }
        if (mysql) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO outbox_events(event_id,event_type,aggregate_id,payload,stream_key,created_at)
                    VALUES (?, 'JOURNAL_COMMITTED', ?, ?, 'bloeco:v2:ledger-events', UTC_TIMESTAMP(3))
                    """)) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, id.toString());
                statement.setString(3, "{\"journal_id\":\"" + id + "\",\"type\":\"" + type.name() + "\"}");
                statement.executeUpdate();
            }
        }
        return id;
    }

    private Operation operation(String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM bank_operations WHERE idempotency_key=?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                String player = result.getString("player_uuid");
                String loan = result.getString("loan_id");
                return new Operation(UUID.fromString(result.getString("operation_id")),
                        result.getString("operation_type"), player == null ? null : UUID.fromString(player),
                        result.getLong("amount_minor"), loan == null ? null : UUID.fromString(loan),
                        UUID.fromString(result.getString("journal_id")));
            }
        }
    }

    private Operation operation(String key, String type, UUID player, long amount,
                                UUID loanId, UUID journalId) throws SQLException {
        Operation operation = new Operation(UUID.randomUUID(), type, player, amount, loanId, journalId);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO bank_operations(operation_id,idempotency_key,operation_type,player_uuid,
                    amount_minor,loan_id,journal_id,created_at_epoch_ms) VALUES (?,?,?,?,?,?,?,?)
                """)) {
            statement.setString(1, operation.id.toString());
            statement.setString(2, key);
            statement.setString(3, type);
            statement.setString(4, player == null ? null : player.toString());
            statement.setLong(5, amount);
            statement.setString(6, loanId == null ? null : loanId.toString());
            statement.setString(7, journalId.toString());
            statement.setLong(8, clock.millis());
            statement.executeUpdate();
        }
        return operation;
    }

    private LoanState loan(UUID loanId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM bank_loans WHERE loan_id=?")) {
            statement.setString(1, loanId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw rejected("贷款不存在");
                return new LoanState(UUID.fromString(result.getString("borrower_uuid")),
                        result.getLong("original_principal_minor"), result.getInt("interest_rate_bps"),
                        result.getLong("outstanding_principal_minor"),
                        result.getLong("outstanding_interest_minor"),
                        Instant.ofEpochMilli(result.getLong("issued_at_epoch_ms")),
                        Instant.ofEpochMilli(result.getLong("due_at_epoch_ms")));
            }
        }
    }

    private long depositBalance(UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(principal_minor + accrued_interest_minor,0)
                FROM bank_deposits WHERE bank_id=? AND player_uuid=?
                """)) {
            statement.setString(1, BANK_ID);
            statement.setString(2, playerId.toString());
            try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getLong(1) : 0L; }
        }
    }

    private void accrueDeposit(UUID playerId) throws SQLException {
        BankingPolicy current = policy();
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT principal_minor,last_interest_epoch_ms FROM bank_deposits
                WHERE bank_id=? AND player_uuid=? AND status='ACTIVE'
                """)) {
            select.setString(1, BANK_ID);
            select.setString(2, playerId.toString());
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) return;
                long principal = result.getLong(1);
                long last = result.getLong(2);
                long days = Math.max(0, (clock.millis() - last) / DAY_MILLIS);
                if (days == 0 || principal == 0 || current.depositRateBasisPoints() == 0) return;
                long interest = Math.multiplyExact(Math.multiplyExact(principal,
                        current.depositRateBasisPoints()), days) / 3_650_000L;
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE bank_deposits SET accrued_interest_minor=accrued_interest_minor+?,
                            last_interest_epoch_ms=?, updated_at_epoch_ms=?
                        WHERE bank_id=? AND player_uuid=?
                        """)) {
                    update.setLong(1, interest);
                    update.setLong(2, last + days * DAY_MILLIS);
                    update.setLong(3, clock.millis());
                    update.setString(4, BANK_ID);
                    update.setString(5, playerId.toString());
                    update.executeUpdate();
                }
            }
        }
    }

    private long playerDebt(UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(outstanding_principal_minor + outstanding_interest_minor),0)
                FROM bank_loans WHERE borrower_uuid=? AND status IN ('ACTIVE','OVERDUE')
                """)) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getLong(1) : 0L; }
        }
    }

    private long balance(AccountId accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance_minor FROM account_balances WHERE account_id=?")) {
            statement.setString(1, accountId.value());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new LedgerException(LedgerException.Code.ACCOUNT_NOT_FOUND, "账户不存在");
                return result.getLong(1);
            }
        }
    }

    private long scalar(String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getLong(1) : 0L;
        }
    }

    private <T> T read(SqlSupplier<T> action) {
        requireOpen();
        try { return action.get(); } catch (SQLException exception) { throw storage("银行数据读取失败", exception); }
    }

    private <T> T write(SqlSupplier<T> action) {
        requireOpen();
        try {
            connection.setAutoCommit(false);
            T result = action.get();
            connection.commit();
            connection.setAutoCommit(true);
            return result;
        } catch (SQLException | RuntimeException exception) {
            try { connection.rollback(); connection.setAutoCommit(true); } catch (SQLException ignored) { }
            if (exception instanceof LedgerException ledger) throw ledger;
            throw storage("银行事务提交失败", exception);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        try {
            if (closeConnection) connection.close();
            closed = true;
        } catch (SQLException exception) { throw storage("关闭银行账本失败", exception); }
    }

    private void requireOpen() {
        if (closed) throw storage("银行账本已经关闭", null);
    }

    private static void requirePositive(Money amount) {
        Objects.requireNonNull(amount, "amount");
        if (amount.minor() <= 0) throw new LedgerException(LedgerException.Code.INVALID_AMOUNT, "金额必须大于零");
    }

    private static long percentage(long amount, int basisPoints) {
        return Money.ofMinor(amount).percentage(basisPoints).minor();
    }

    private static LedgerException insufficient(String message) {
        return new LedgerException(LedgerException.Code.INSUFFICIENT_FUNDS, message);
    }

    private static LedgerException rejected(String message) {
        return new LedgerException(LedgerException.Code.POLICY_REJECTED, message);
    }

    private static LedgerException storage(String message, Exception cause) {
        return new LedgerException(LedgerException.Code.STORAGE_UNAVAILABLE, message, cause);
    }

    private String insertIgnore(String sql) {
        return mysql ? sql.replaceFirst("INSERT INTO", "INSERT IGNORE INTO")
                : sql.replaceFirst("INSERT INTO", "INSERT OR IGNORE INTO");
    }

    private static Connection open(Path database) {
        try {
            return DriverManager.getConnection("jdbc:sqlite:" + Objects.requireNonNull(database, "database"));
        } catch (SQLException exception) {
            throw storage("unable to open SQLite banking store", exception);
        }
    }

    @FunctionalInterface private interface SqlSupplier<T> { T get() throws SQLException; }
    private record LoanState(UUID playerId, long originalPrincipal, int interestRateBasisPoints,
                             long principal, long interest, Instant issuedAt, Instant dueAt) {}
    private record Operation(UUID id, String type, UUID playerId, long amount, UUID loanId, UUID journalId) {
        BankingReceipt receipt() { return new BankingReceipt(id, journalId, Money.ofMinor(amount)); }
        BankingReceipt receipt(String expectedType, UUID expectedPlayer, long expectedAmount) {
            validate(expectedType, expectedPlayer, expectedAmount);
            return receipt();
        }
        BankingReceipt receipt(String expectedType, UUID expectedPlayer) {
            if (!type.equals(expectedType) || !Objects.equals(playerId, expectedPlayer)) {
                throw new LedgerException(LedgerException.Code.IDEMPOTENCY_CONFLICT,
                        "幂等键已用于其他银行操作");
            }
            return receipt();
        }
        private void validate(String expectedType, UUID expectedPlayer, long expectedAmount) {
            if (!type.equals(expectedType) || !Objects.equals(playerId, expectedPlayer) || amount != expectedAmount) {
                throw new LedgerException(LedgerException.Code.IDEMPOTENCY_CONFLICT, "幂等键已用于其他银行操作");
            }
        }
    }
}
