package com.blocke.centraleconomy.storage.sqlite;

import com.blocke.bloeco.api.InstitutionCapability;
import com.blocke.centraleconomy.application.integration.EcosystemStore;
import com.blocke.centraleconomy.application.integration.InstitutionRecord;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.ledger.LedgerException;

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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
