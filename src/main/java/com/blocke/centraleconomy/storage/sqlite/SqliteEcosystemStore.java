package com.blocke.centraleconomy.storage.sqlite;

import com.blocke.bloeco.api.InstitutionCapability;
import com.blocke.centraleconomy.application.integration.EcosystemStore;
import com.blocke.centraleconomy.application.integration.InstitutionRecord;
import com.blocke.centraleconomy.application.integration.SettlementRecord;
import com.blocke.centraleconomy.application.integration.ReservationRecord;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.ledger.LedgerException;
import com.blocke.centraleconomy.domain.money.Money;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** SQLite development/test implementation of the ecosystem institution boundary. */
public final class SqliteEcosystemStore implements EcosystemStore {
    private final Connection connection;
    private boolean closed;

    public SqliteEcosystemStore(Path databasePath) {
        try {
            Path absolute = databasePath.toAbsolutePath();
            if (absolute.getParent() != null) Files.createDirectories(absolute.getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            configure();
            SqliteSchema.apply(connection);
        } catch (SQLException | IOException exception) {
            throw storageFailure("unable to open SQLite ecosystem store", exception);
        }
    }

    @Override
    public synchronized InstitutionRecord registerOrResume(InstitutionRegistration registration) {
        requireOpen();
        try {
            beginImmediate();
            Optional<InstitutionRecord> existing = institution(registration.clientId());
            if (existing.isPresent() && (!existing.get().pluginName().equals(registration.pluginName())
                    || !existing.get().pluginMainClass().equals(registration.pluginMainClass()))) {
                throw new LedgerException(LedgerException.Code.UNAUTHORIZED,
                        "institution is already owned by a different plugin");
            }
            if (existing.isEmpty()) insertInstitution(registration);
            else updateInstitution(registration);
            replaceCapabilities(registration);
            createAccounts(registration);
            InstitutionRecord result = institution(registration.clientId()).orElseThrow();
            commit();
            return result;
        } catch (SQLException | RuntimeException exception) {
            rollback();
            if (exception instanceof LedgerException ledgerException) throw ledgerException;
            throw storageFailure("unable to register institution", exception);
        }
    }

    @Override
    public synchronized Optional<InstitutionRecord> institution(String clientId) {
        requireOpen();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM institutions WHERE institution_id=?")) {
            statement.setString(1, clientId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new InstitutionRecord(
                        row.getString("institution_id"), row.getString("display_name"),
                        row.getString("plugin_name"), row.getString("plugin_main_class"),
                        row.getString("plugin_version"), row.getInt("api_major"),
                        InstitutionRecord.Status.valueOf(row.getString("status")),
                        capabilities(clientId), accounts(clientId),
                        Instant.ofEpochMilli(row.getLong("created_at_epoch_ms")),
                        Instant.ofEpochMilli(row.getLong("updated_at_epoch_ms"))));
            }
        } catch (SQLException exception) {
            throw storageFailure("unable to read institution", exception);
        }
    }

    @Override
    public synchronized SettlementRecord commitSettlement(SettlementCommand command) {
        requireOpen();
        try {
            beginImmediate();
            Optional<SettlementRecord> replay = findOperation(command.clientId(), command.idempotencyKey());
            if (replay.isPresent()) {
                requireMatchingFingerprint(replay.get(), command.fingerprint());
                commit();
                return replay.get();
            }
            requireOwnedAccount(command.clientId(), command.institutionAccountId());
            List<BalanceDelta> deltas = new ArrayList<>();
            if (command.type() == SettlementRecord.Type.CHARGE) {
                long playerDebit = Math.addExact(command.principal().minor(),
                        Math.addExact(command.tax().minor(), command.fee().minor()));
                deltas.add(new BalanceDelta(AccountId.player(command.playerId()), -playerDebit));
                deltas.add(new BalanceDelta(command.institutionAccountId(), command.institutionNet().minor()));
                addIfNonZero(deltas, AccountId.taxRevenue(), command.tax().minor());
                addIfNonZero(deltas, AccountId.feeRevenue(), command.fee().minor());
            } else if (command.type() == SettlementRecord.Type.PAYOUT) {
                deltas.add(new BalanceDelta(command.institutionAccountId(), -command.principal().minor()));
                deltas.add(new BalanceDelta(AccountId.player(command.playerId()), command.principal().minor()));
            } else {
                throw new LedgerException(LedgerException.Code.INVALID_JOURNAL,
                        "refunds must use the refund operation");
            }
            applyJournal(command.journalId(), command.clientId(), command.idempotencyKey(),
                    command.displayMemo(), command.committedAt(), deltas);
            SettlementRecord result = new SettlementRecord(
                    command.operationId(), command.journalId(), command.clientId(), command.type(), command.kind(),
                    command.playerId(), command.institutionAccountId(), command.principal(), command.tax(),
                    command.fee(), command.institutionNet(), command.businessType(), command.businessReference(),
                    command.displayMemo(), command.idempotencyKey(), command.fingerprint(), command.committedAt(),
                    null, command.taxRuleVersion());
            insertOperation(result);
            commit();
            return result;
        } catch (SQLException | RuntimeException exception) {
            rollback();
            if (exception instanceof LedgerException ledgerException) throw ledgerException;
            if (exception instanceof ArithmeticException arithmetic) throw new LedgerException(
                    LedgerException.Code.INVALID_AMOUNT, "settlement amount exceeds the supported range", arithmetic);
            throw storageFailure("unable to commit ecosystem settlement", exception);
        }
    }

    @Override
    public synchronized SettlementRecord commitRefund(RefundCommand command) {
        requireOpen();
        try {
            beginImmediate();
            Optional<SettlementRecord> replay = findOperation(command.clientId(), command.idempotencyKey());
            if (replay.isPresent()) {
                requireMatchingFingerprint(replay.get(), command.fingerprint());
                commit();
                return replay.get();
            }
            SettlementRecord original = operation(command.originalOperationId()).orElseThrow(() ->
                    new LedgerException(LedgerException.Code.INVALID_JOURNAL,
                            "original settlement does not exist"));
            if (!original.clientId().equals(command.clientId()) || original.type() != SettlementRecord.Type.CHARGE) {
                throw new LedgerException(LedgerException.Code.UNAUTHORIZED,
                        "original charge is not owned by the caller");
            }
            RefundTotals refunded = refunded(command.originalOperationId());
            long remainingPrincipal = Math.subtractExact(original.principal().minor(), refunded.principal());
            long remainingTax = Math.subtractExact(original.tax().minor(), refunded.tax());
            if (command.principal().minor() > remainingPrincipal) throw new LedgerException(
                    LedgerException.Code.INVALID_AMOUNT, "refund exceeds the unrefunded principal");
            long tax = command.principal().minor() == remainingPrincipal ? remainingTax
                    : proportional(original.tax().minor(), command.principal().minor(), original.principal().minor());
            tax = Math.min(tax, remainingTax);
            List<BalanceDelta> deltas = new ArrayList<>();
            deltas.add(new BalanceDelta(original.institutionAccountId(), -command.principal().minor()));
            addIfNonZero(deltas, AccountId.taxRevenue(), -tax);
            deltas.add(new BalanceDelta(AccountId.player(original.playerId()),
                    Math.addExact(command.principal().minor(), tax)));
            applyJournal(command.journalId(), command.clientId(), command.idempotencyKey(),
                    command.displayMemo(), command.committedAt(), deltas);
            SettlementRecord result = new SettlementRecord(command.operationId(), command.journalId(),
                    command.clientId(), SettlementRecord.Type.REFUND, original.kind(), original.playerId(),
                    original.institutionAccountId(), command.principal(), Money.ofMinor(tax), Money.ofMinor(0),
                    command.principal(), command.businessType(), command.businessReference(), command.displayMemo(),
                    command.idempotencyKey(), command.fingerprint(), command.committedAt(),
                    original.operationId(), original.taxRuleVersion());
            insertOperation(result);
            commit();
            return result;
        } catch (SQLException | RuntimeException exception) {
            rollback();
            if (exception instanceof LedgerException ledgerException) throw ledgerException;
            if (exception instanceof ArithmeticException arithmetic) throw new LedgerException(
                    LedgerException.Code.INVALID_AMOUNT, "refund amount exceeds the supported range", arithmetic);
            throw storageFailure("unable to commit ecosystem refund", exception);
        }
    }

    @Override
    public synchronized Optional<SettlementRecord> findOperation(String clientId, String idempotencyKey) {
        requireOpen();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM ecosystem_operations WHERE institution_id=? AND idempotency_key=?")) {
            statement.setString(1, clientId);
            statement.setString(2, idempotencyKey);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readOperation(row)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw storageFailure("unable to find ecosystem operation", exception);
        }
    }

    @Override
    public synchronized List<SettlementRecord> recentTransactions(
            String clientId, String accountPurpose, int limit) {
        requireOpen();
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        AccountId account = AccountId.plugin(clientId, accountPurpose);
        requireOwnedAccount(clientId, account);
        List<SettlementRecord> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM ecosystem_operations
                WHERE institution_id=? AND institution_account_id=?
                ORDER BY committed_at_epoch_ms DESC, operation_id DESC LIMIT ?
                """)) {
            statement.setString(1, clientId);
            statement.setString(2, account.value());
            statement.setInt(3, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(readOperation(rows));
            }
            return List.copyOf(result);
        } catch (SQLException exception) {
            throw storageFailure("unable to read ecosystem transactions", exception);
        }
    }

    @Override
    public synchronized long balance(AccountId accountId) {
        requireOpen();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance_minor FROM account_balances WHERE account_id=?")) {
            statement.setString(1, accountId.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new LedgerException(LedgerException.Code.ACCOUNT_NOT_FOUND,
                        "account does not exist: " + accountId.value());
                return row.getLong(1);
            }
        } catch (SQLException exception) {
            throw storageFailure("unable to read ecosystem balance", exception);
        }
    }

    @Override
    public synchronized long reservedBalance(UUID playerId) {
        return balanceColumn(AccountId.player(playerId), "reserved_minor");
    }

    @Override
    public synchronized long availableBalance(UUID playerId) {
        AccountId account = AccountId.player(playerId);
        return Math.subtractExact(balanceColumn(account, "balance_minor"),
                balanceColumn(account, "reserved_minor"));
    }

    @Override
    public synchronized ReservationRecord reserve(ReservationCommand command) {
        requireOpen();
        try {
            beginImmediate();
            Optional<ReservationRecord> replay = reservationByKey(command.clientId(), command.idempotencyKey());
            if (replay.isPresent()) {
                if (!replay.get().fingerprint().equals(command.fingerprint())) throw new LedgerException(
                        LedgerException.Code.IDEMPOTENCY_CONFLICT, "reservation key conflicts with another request");
                commit();
                return replay.get();
            }
            long total = Math.addExact(command.principal().minor(), command.tax().minor());
            AccountId player = AccountId.player(command.playerId());
            long balance = balanceColumn(player, "balance_minor");
            long reserved = balanceColumn(player, "reserved_minor");
            if (Math.subtractExact(balance, reserved) < total) throw new LedgerException(
                    LedgerException.Code.INSUFFICIENT_FUNDS, "player has insufficient available funds");
            setReserved(player, Math.addExact(reserved, total));
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO fund_reservations(reservation_id,institution_id,player_uuid,principal_minor,
                        tax_minor,tax_rule_version,status,expires_at_epoch_ms,created_at_epoch_ms,
                        updated_at_epoch_ms,reserve_idempotency_key,reserve_fingerprint)
                    VALUES (?,?,?,?,?,?,'ACTIVE',?,?,?,?,?)
                    """)) {
                statement.setString(1, command.reservationId().toString());
                statement.setString(2, command.clientId());
                statement.setString(3, command.playerId().toString());
                statement.setLong(4, command.principal().minor());
                statement.setLong(5, command.tax().minor());
                statement.setString(6, command.taxRuleVersion().toString());
                statement.setLong(7, command.expiresAt().toEpochMilli());
                statement.setLong(8, command.createdAt().toEpochMilli());
                statement.setLong(9, command.createdAt().toEpochMilli());
                statement.setString(10, command.idempotencyKey());
                statement.setString(11, command.fingerprint());
                statement.executeUpdate();
            }
            ReservationRecord result = reservationRow(command.reservationId()).orElseThrow();
            commit();
            return result;
        } catch (SQLException | RuntimeException exception) {
            rollback();
            if (exception instanceof LedgerException ledgerException) throw ledgerException;
            throw storageFailure("unable to reserve player funds", exception);
        }
    }

    @Override
    public synchronized SettlementRecord capture(CaptureCommand command) {
        requireOpen();
        try {
            beginImmediate();
            Optional<SettlementRecord> replay = findOperation(command.clientId(), command.idempotencyKey());
            if (replay.isPresent()) {
                requireMatchingFingerprint(replay.get(), command.fingerprint());
                commit();
                return replay.get();
            }
            ReservationRecord reservation = activeReservation(command.clientId(), command.reservationId(),
                    command.committedAt());
            requireOwnedAccount(command.clientId(), command.institutionAccountId());
            long total = reservation.reservedTotal().minor();
            AccountId player = AccountId.player(reservation.playerId());
            setReserved(player, Math.subtractExact(balanceColumn(player, "reserved_minor"), total));
            List<BalanceDelta> deltas = new ArrayList<>();
            deltas.add(new BalanceDelta(player, -total));
            deltas.add(new BalanceDelta(command.institutionAccountId(), reservation.principal().minor()));
            addIfNonZero(deltas, AccountId.taxRevenue(), reservation.tax().minor());
            applyJournal(command.journalId(), command.clientId(), command.idempotencyKey(), command.displayMemo(),
                    command.committedAt(), deltas);
            SettlementRecord result = new SettlementRecord(command.operationId(), command.journalId(),
                    command.clientId(), SettlementRecord.Type.CHARGE,
                    com.blocke.bloeco.api.SettlementKind.PURCHASE, reservation.playerId(),
                    command.institutionAccountId(), reservation.principal(), reservation.tax(), Money.ofMinor(0),
                    reservation.principal(), command.businessType(), command.businessReference(),
                    command.displayMemo(), command.idempotencyKey(), command.fingerprint(), command.committedAt(),
                    null, reservation.taxRuleVersion());
            insertOperation(result);
            updateReservationStatus(command.reservationId(), ReservationRecord.Status.CAPTURED,
                    command.committedAt(), command.operationId());
            commit();
            return result;
        } catch (SQLException | RuntimeException exception) {
            rollback();
            if (exception instanceof LedgerException ledgerException) throw ledgerException;
            throw storageFailure("unable to capture player funds", exception);
        }
    }

    @Override
    public synchronized ReservationRecord release(ReservationTransition command) {
        requireOpen();
        try {
            beginImmediate();
            ReservationRecord record = reservationRow(command.reservationId()).orElseThrow(() ->
                    new LedgerException(LedgerException.Code.INVALID_JOURNAL, "reservation does not exist"));
            if (!record.clientId().equals(command.clientId())) throw new LedgerException(
                    LedgerException.Code.UNAUTHORIZED, "reservation is not owned by caller");
            ReleaseIdentity previous = releaseIdentity(command.reservationId());
            if (previous.key() != null) {
                if (!previous.key().equals(command.idempotencyKey())
                        || !previous.fingerprint().equals(command.fingerprint())) throw new LedgerException(
                        LedgerException.Code.IDEMPOTENCY_CONFLICT, "release key conflicts with prior request");
                commit();
                return record;
            }
            record = expireIfNeeded(record, command.transitionedAt());
            if (record.status() == ReservationRecord.Status.EXPIRED) {
                commit();
                return record;
            }
            if (record.status() != ReservationRecord.Status.ACTIVE) throw new LedgerException(
                    LedgerException.Code.POLICY_REJECTED, "reservation is no longer active");
            releaseReserved(record);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE fund_reservations SET status='RELEASED',updated_at_epoch_ms=?,
                        release_idempotency_key=?,release_fingerprint=? WHERE reservation_id=? AND status='ACTIVE'
                    """)) {
                statement.setLong(1, command.transitionedAt().toEpochMilli());
                statement.setString(2, command.idempotencyKey());
                statement.setString(3, command.fingerprint());
                statement.setString(4, command.reservationId().toString());
                if (statement.executeUpdate() != 1) throw new LedgerException(
                        LedgerException.Code.POLICY_REJECTED, "reservation changed during release");
            }
            ReservationRecord result = reservationRow(command.reservationId()).orElseThrow();
            commit();
            return result;
        } catch (SQLException | RuntimeException exception) {
            rollback();
            if (exception instanceof LedgerException ledgerException) throw ledgerException;
            throw storageFailure("unable to release player funds", exception);
        }
    }

    @Override
    public synchronized Optional<ReservationRecord> reservation(String clientId, UUID reservationId, Instant now) {
        requireOpen();
        try {
            beginImmediate();
            Optional<ReservationRecord> found = reservationRow(reservationId);
            if (found.isEmpty() || !found.get().clientId().equals(clientId)) {
                commit();
                return Optional.empty();
            }
            ReservationRecord result = expireIfNeeded(found.get(), now);
            commit();
            return Optional.of(result);
        } catch (SQLException | RuntimeException exception) {
            rollback();
            if (exception instanceof LedgerException ledgerException) throw ledgerException;
            throw storageFailure("unable to read reservation", exception);
        }
    }

    private void insertInstitution(InstitutionRegistration registration) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO institutions(institution_id,display_name,plugin_name,plugin_main_class,
                    plugin_version,api_major,status,created_at_epoch_ms,updated_at_epoch_ms)
                VALUES (?,?,?,?,?,?,'ACTIVE',?,?)
                """)) {
            bindInstitution(statement, registration);
            statement.executeUpdate();
        }
    }

    private void updateInstitution(InstitutionRegistration registration) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE institutions SET display_name=?,plugin_version=?,api_major=?,updated_at_epoch_ms=?
                WHERE institution_id=?
                """)) {
            statement.setString(1, registration.displayName());
            statement.setString(2, registration.pluginVersion());
            statement.setInt(3, registration.apiMajor());
            statement.setLong(4, registration.registeredAt().toEpochMilli());
            statement.setString(5, registration.clientId());
            statement.executeUpdate();
        }
    }

    private static void bindInstitution(
            PreparedStatement statement, InstitutionRegistration registration) throws SQLException {
        statement.setString(1, registration.clientId());
        statement.setString(2, registration.displayName());
        statement.setString(3, registration.pluginName());
        statement.setString(4, registration.pluginMainClass());
        statement.setString(5, registration.pluginVersion());
        statement.setInt(6, registration.apiMajor());
        statement.setLong(7, registration.registeredAt().toEpochMilli());
        statement.setLong(8, registration.registeredAt().toEpochMilli());
    }

    private void replaceCapabilities(InstitutionRegistration registration) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM institution_capabilities WHERE institution_id=?")) {
            delete.setString(1, registration.clientId());
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO institution_capabilities(institution_id,capability) VALUES (?,?)")) {
            for (InstitutionCapability capability : registration.capabilities()) {
                insert.setString(1, registration.clientId());
                insert.setString(2, capability.name());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void createAccounts(InstitutionRegistration registration) throws SQLException {
        for (String purpose : registration.accountPurposes()) {
            AccountId accountId = AccountId.plugin(registration.clientId(), purpose);
            try (PreparedStatement account = connection.prepareStatement("""
                    INSERT OR IGNORE INTO accounts(account_id,account_class,owner_type,owner_id,purpose,
                        status,permits_negative,parent_account_id,created_at_epoch_ms)
                    VALUES (?,'INSTITUTION','PLUGIN',?,?,'ACTIVE',0,NULL,?)
                    """)) {
                account.setString(1, accountId.value());
                account.setString(2, registration.clientId());
                account.setString(3, purpose);
                account.setLong(4, registration.registeredAt().toEpochMilli());
                account.executeUpdate();
            }
            try (PreparedStatement balance = connection.prepareStatement(
                    "INSERT OR IGNORE INTO account_balances(account_id,balance_minor) VALUES (?,0)")) {
                balance.setString(1, accountId.value());
                balance.executeUpdate();
            }
        }
    }

    private Set<InstitutionCapability> capabilities(String clientId) throws SQLException {
        Set<InstitutionCapability> result = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT capability FROM institution_capabilities WHERE institution_id=? ORDER BY capability")) {
            statement.setString(1, clientId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(InstitutionCapability.valueOf(rows.getString(1)));
            }
        }
        return Set.copyOf(result);
    }

    private Map<String, AccountId> accounts(String clientId) throws SQLException {
        Map<String, AccountId> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT purpose,account_id FROM accounts
                WHERE owner_type='PLUGIN' AND owner_id=? ORDER BY purpose
                """)) {
            statement.setString(1, clientId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.put(rows.getString(1), new AccountId(rows.getString(2)));
            }
        }
        return Map.copyOf(result);
    }

    private Optional<SettlementRecord> operation(UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM ecosystem_operations WHERE operation_id=?")) {
            statement.setString(1, operationId.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readOperation(row)) : Optional.empty();
            }
        }
    }

    private Optional<ReservationRecord> reservationByKey(String clientId, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM fund_reservations WHERE institution_id=? AND reserve_idempotency_key=?")) {
            statement.setString(1, clientId);
            statement.setString(2, key);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readReservation(row)) : Optional.empty();
            }
        }
    }

    private Optional<ReservationRecord> reservationRow(UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM fund_reservations WHERE reservation_id=?")) {
            statement.setString(1, id.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readReservation(row)) : Optional.empty();
            }
        }
    }

    private static ReservationRecord readReservation(ResultSet row) throws SQLException {
        return new ReservationRecord(UUID.fromString(row.getString("reservation_id")),
                row.getString("institution_id"), UUID.fromString(row.getString("player_uuid")),
                Money.ofMinor(row.getLong("principal_minor")), Money.ofMinor(row.getLong("tax_minor")),
                UUID.fromString(row.getString("tax_rule_version")),
                ReservationRecord.Status.valueOf(row.getString("status")),
                Instant.ofEpochMilli(row.getLong("expires_at_epoch_ms")),
                Instant.ofEpochMilli(row.getLong("created_at_epoch_ms")),
                Instant.ofEpochMilli(row.getLong("updated_at_epoch_ms")),
                row.getString("reserve_idempotency_key"), row.getString("reserve_fingerprint"));
    }

    private ReservationRecord activeReservation(String clientId, UUID id, Instant now) throws SQLException {
        ReservationRecord record = reservationRow(id).orElseThrow(() ->
                new LedgerException(LedgerException.Code.INVALID_JOURNAL, "reservation does not exist"));
        if (!record.clientId().equals(clientId)) throw new LedgerException(
                LedgerException.Code.UNAUTHORIZED, "reservation is not owned by caller");
        record = expireIfNeeded(record, now);
        if (record.status() != ReservationRecord.Status.ACTIVE) {
            if (record.status() == ReservationRecord.Status.EXPIRED) commit();
            throw new LedgerException(LedgerException.Code.POLICY_REJECTED,
                    "reservation is no longer active");
        }
        return record;
    }

    private ReservationRecord expireIfNeeded(ReservationRecord record, Instant now) throws SQLException {
        if (record.status() == ReservationRecord.Status.ACTIVE && !record.expiresAt().isAfter(now)) {
            releaseReserved(record);
            updateReservationStatus(record.reservationId(), ReservationRecord.Status.EXPIRED, now, null);
            return reservationRow(record.reservationId()).orElseThrow();
        }
        return record;
    }

    private void releaseReserved(ReservationRecord record) throws SQLException {
        AccountId player = AccountId.player(record.playerId());
        long current = balanceColumn(player, "reserved_minor");
        setReserved(player, Math.subtractExact(current, record.reservedTotal().minor()));
    }

    private void updateReservationStatus(UUID id, ReservationRecord.Status status, Instant at,
                                         UUID captureOperationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE fund_reservations SET status=?,updated_at_epoch_ms=?,capture_operation_id=?
                WHERE reservation_id=? AND status='ACTIVE'
                """)) {
            statement.setString(1, status.name());
            statement.setLong(2, at.toEpochMilli());
            statement.setString(3, captureOperationId == null ? null : captureOperationId.toString());
            statement.setString(4, id.toString());
            if (statement.executeUpdate() != 1) throw new LedgerException(
                    LedgerException.Code.POLICY_REJECTED, "reservation changed during transition");
        }
    }

    private ReleaseIdentity releaseIdentity(UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT release_idempotency_key,release_fingerprint FROM fund_reservations WHERE reservation_id=?")) {
            statement.setString(1, id.toString());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return new ReleaseIdentity(null, null);
                return new ReleaseIdentity(row.getString(1), row.getString(2));
            }
        }
    }

    private long balanceColumn(AccountId accountId, String column) {
        if (!column.equals("balance_minor") && !column.equals("reserved_minor")) {
            throw new IllegalArgumentException("unsupported balance column");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + column + " FROM account_balances WHERE account_id=?")) {
            statement.setString(1, accountId.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new LedgerException(LedgerException.Code.ACCOUNT_NOT_FOUND,
                        "account does not exist: " + accountId.value());
                return row.getLong(1);
            }
        } catch (SQLException exception) {
            throw storageFailure("unable to read balance", exception);
        }
    }

    private void setReserved(AccountId accountId, long reserved) throws SQLException {
        if (reserved < 0) throw new LedgerException(LedgerException.Code.INTEGRITY_FAILURE,
                "reserved balance would become negative");
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE account_balances SET reserved_minor=? WHERE account_id=?")) {
            statement.setLong(1, reserved);
            statement.setString(2, accountId.value());
            if (statement.executeUpdate() != 1) throw new LedgerException(
                    LedgerException.Code.ACCOUNT_NOT_FOUND, "account does not exist: " + accountId.value());
        }
    }

    private SettlementRecord readOperation(ResultSet row) throws SQLException {
        String original = row.getString("original_operation_id");
        String taxRule = row.getString("tax_rule_version");
        return new SettlementRecord(
                UUID.fromString(row.getString("operation_id")), UUID.fromString(row.getString("journal_id")),
                row.getString("institution_id"), SettlementRecord.Type.valueOf(row.getString("operation_type")),
                com.blocke.bloeco.api.SettlementKind.valueOf(row.getString("settlement_kind")),
                UUID.fromString(row.getString("player_uuid")),
                new AccountId(row.getString("institution_account_id")),
                Money.ofMinor(row.getLong("principal_minor")), Money.ofMinor(row.getLong("tax_minor")),
                Money.ofMinor(row.getLong("fee_minor")), Money.ofMinor(row.getLong("institution_net_minor")),
                row.getString("business_type"), row.getString("business_reference"),
                row.getString("display_memo"), row.getString("idempotency_key"),
                row.getString("request_fingerprint"), Instant.ofEpochMilli(row.getLong("committed_at_epoch_ms")),
                original == null ? null : UUID.fromString(original),
                taxRule == null ? null : UUID.fromString(taxRule));
    }

    private void insertOperation(SettlementRecord record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ecosystem_operations(operation_id,institution_id,idempotency_key,
                    request_fingerprint,operation_type,settlement_kind,player_uuid,institution_account_id,
                    principal_minor,tax_minor,fee_minor,institution_net_minor,tax_rule_version,business_type,
                    business_reference,display_memo,journal_id,original_operation_id,committed_at_epoch_ms)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            statement.setString(1, record.operationId().toString());
            statement.setString(2, record.clientId());
            statement.setString(3, record.idempotencyKey());
            statement.setString(4, record.fingerprint());
            statement.setString(5, record.type().name());
            statement.setString(6, record.kind().name());
            statement.setString(7, record.playerId().toString());
            statement.setString(8, record.institutionAccountId().value());
            statement.setLong(9, record.principal().minor());
            statement.setLong(10, record.tax().minor());
            statement.setLong(11, record.fee().minor());
            statement.setLong(12, record.institutionNet().minor());
            statement.setString(13, record.taxRuleVersion() == null ? null : record.taxRuleVersion().toString());
            statement.setString(14, record.businessType());
            statement.setString(15, record.businessReference());
            statement.setString(16, record.displayMemo());
            statement.setString(17, record.journalId().toString());
            statement.setString(18, record.originalOperationId() == null ? null
                    : record.originalOperationId().toString());
            statement.setLong(19, record.committedAt().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private void applyJournal(UUID journalId, String clientId, String idempotencyKey, String memo,
                              Instant committedAt, List<BalanceDelta> rawDeltas) throws SQLException {
        Map<AccountId, Long> deltas = new LinkedHashMap<>();
        for (BalanceDelta delta : rawDeltas) deltas.merge(delta.accountId(), delta.amount(), Math::addExact);
        deltas.entrySet().removeIf(entry -> entry.getValue() == 0);
        if (deltas.size() < 2 || deltas.values().stream().mapToLong(Long::longValue).sum() != 0) {
            throw new LedgerException(LedgerException.Code.INVALID_JOURNAL,
                    "ecosystem settlement journal is not balanced");
        }
        Map<AccountId, Long> updated = new HashMap<>();
        for (Map.Entry<AccountId, Long> delta : deltas.entrySet()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT a.status,a.permits_negative,b.balance_minor,b.reserved_minor
                    FROM accounts a JOIN account_balances b ON b.account_id=a.account_id
                    WHERE a.account_id=?
                    """)) {
                statement.setString(1, delta.getKey().value());
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) throw new LedgerException(LedgerException.Code.ACCOUNT_NOT_FOUND,
                            "account does not exist: " + delta.getKey().value());
                    if (!"ACTIVE".equals(row.getString(1))) throw new LedgerException(
                            LedgerException.Code.ACCOUNT_FROZEN, "account is not active");
                    long value = Math.addExact(row.getLong(3), delta.getValue());
                    long availableAfter = Math.subtractExact(value, row.getLong(4));
                    if (row.getInt(2) == 0 && availableAfter < 0) throw new LedgerException(
                            LedgerException.Code.INSUFFICIENT_FUNDS,
                            "account has insufficient funds: " + delta.getKey().value());
                    updated.put(delta.getKey(), value);
                }
            }
        }
        try (PreparedStatement journal = connection.prepareStatement("""
                INSERT INTO journal_entries(entry_id,journal_type,memo,client_id,idempotency_key,
                    reversal_of_entry_id,created_at_epoch_ms) VALUES (?,'PLUGIN_TRANSFER',?,?,?,?,?)
                """)) {
            journal.setString(1, journalId.toString());
            journal.setString(2, memo);
            journal.setString(3, clientId);
            journal.setString(4, idempotencyKey);
            journal.setString(5, null);
            journal.setLong(6, committedAt.toEpochMilli());
            journal.executeUpdate();
        }
        try (PreparedStatement posting = connection.prepareStatement(
                "INSERT INTO postings(entry_id,line_no,account_id,amount_minor) VALUES (?,?,?,?)")) {
            int line = 0;
            for (Map.Entry<AccountId, Long> delta : deltas.entrySet()) {
                posting.setString(1, journalId.toString());
                posting.setInt(2, ++line);
                posting.setString(3, delta.getKey().value());
                posting.setLong(4, delta.getValue());
                posting.addBatch();
            }
            posting.executeBatch();
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE account_balances SET balance_minor=? WHERE account_id=?")) {
            for (Map.Entry<AccountId, Long> balance : updated.entrySet()) {
                update.setLong(1, balance.getValue());
                update.setString(2, balance.getKey().value());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private RefundTotals refunded(UUID originalOperationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(principal_minor),0),COALESCE(SUM(tax_minor),0)
                FROM ecosystem_operations WHERE original_operation_id=? AND operation_type='REFUND'
                """)) {
            statement.setString(1, originalOperationId.toString());
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return new RefundTotals(row.getLong(1), row.getLong(2));
            }
        }
    }

    private void requireOwnedAccount(String clientId, AccountId accountId) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM accounts
                WHERE account_id=? AND owner_type='PLUGIN' AND owner_id=? AND status='ACTIVE'
                """)) {
            statement.setString(1, accountId.value());
            statement.setString(2, clientId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || row.getInt(1) != 1) throw new LedgerException(
                        LedgerException.Code.UNAUTHORIZED, "institution account is not owned by the caller");
            }
        } catch (SQLException exception) {
            throw storageFailure("unable to verify institution account", exception);
        }
    }

    private static void requireMatchingFingerprint(SettlementRecord replay, String fingerprint) {
        if (!replay.fingerprint().equals(fingerprint)) throw new LedgerException(
                LedgerException.Code.IDEMPOTENCY_CONFLICT,
                "idempotency key is already bound to a different request");
    }

    private static void addIfNonZero(List<BalanceDelta> deltas, AccountId accountId, long amount) {
        if (amount != 0) deltas.add(new BalanceDelta(accountId, amount));
    }

    private static long proportional(long total, long part, long whole) {
        return BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(part))
                .divide(BigDecimal.valueOf(whole), 0, RoundingMode.HALF_UP).longValueExact();
    }

    private record BalanceDelta(AccountId accountId, long amount) {}
    private record RefundTotals(long principal, long tax) {}
    private record ReleaseIdentity(String key, String fingerprint) {}

    private void configure() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA synchronous=FULL");
        }
    }

    private void beginImmediate() throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute("BEGIN IMMEDIATE"); }
    }

    private void commit() throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute("COMMIT"); }
    }

    private void rollback() {
        try (Statement statement = connection.createStatement()) { statement.execute("ROLLBACK"); }
        catch (SQLException ignored) { }
    }

    private void requireOpen() {
        if (closed) throw new LedgerException(LedgerException.Code.STORAGE_UNAVAILABLE,
                "ecosystem store is closed");
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        try {
            connection.close();
            closed = true;
        } catch (SQLException exception) {
            throw storageFailure("unable to close SQLite ecosystem store", exception);
        }
    }

    private static LedgerException storageFailure(String message, Exception cause) {
        return new LedgerException(LedgerException.Code.STORAGE_UNAVAILABLE, message, cause);
    }
}
