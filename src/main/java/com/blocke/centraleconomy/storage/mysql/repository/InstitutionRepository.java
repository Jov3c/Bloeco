package com.blocke.centraleconomy.storage.mysql.repository;

import com.blocke.bloeco.api.InstitutionCapability;
import com.blocke.centraleconomy.application.integration.EcosystemStore;
import com.blocke.centraleconomy.application.integration.InstitutionRecord;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.ledger.LedgerException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Institution identity, capability and account persistence inside caller-owned transactions. */
public final class InstitutionRepository {
    public InstitutionRecord registerOrResume(Connection connection, EcosystemStore.InstitutionRegistration value)
            throws SQLException {
        Optional<InstitutionRecord> existing = find(connection, value.clientId(), true);
        if (existing.isPresent() && (!existing.get().pluginName().equals(value.pluginName())
                || !existing.get().pluginMainClass().equals(value.pluginMainClass()))) {
            throw new LedgerException(LedgerException.Code.UNAUTHORIZED,
                    "institution is already owned by a different plugin");
        }
        if (existing.isEmpty()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO institutions(institution_id,display_name,plugin_version,status,created_at,updated_at,
                        plugin_name,plugin_main_class,api_major) VALUES (?,?,?,'ACTIVE',?,?,?,?,?)
                    """)) {
                statement.setString(1, value.clientId()); statement.setString(2, value.displayName());
                statement.setString(3, value.pluginVersion());
                statement.setTimestamp(4, Timestamp.from(value.registeredAt()));
                statement.setTimestamp(5, Timestamp.from(value.registeredAt()));
                statement.setString(6, value.pluginName()); statement.setString(7, value.pluginMainClass());
                statement.setInt(8, value.apiMajor()); statement.executeUpdate();
            }
        } else {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE institutions SET display_name=?,plugin_version=?,api_major=?,updated_at=?
                    WHERE institution_id=?
                    """)) {
                statement.setString(1, value.displayName()); statement.setString(2, value.pluginVersion());
                statement.setInt(3, value.apiMajor()); statement.setTimestamp(4, Timestamp.from(value.registeredAt()));
                statement.setString(5, value.clientId()); statement.executeUpdate();
            }
        }
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM institution_capabilities WHERE institution_id=?")) {
            delete.setString(1, value.clientId()); delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO institution_capabilities(institution_id,capability) VALUES (?,?)")) {
            for (InstitutionCapability capability : value.capabilities()) {
                insert.setString(1, value.clientId()); insert.setString(2, capability.name()); insert.addBatch();
            }
            insert.executeBatch();
        }
        for (String purpose : value.accountPurposes()) createAccount(connection, value, purpose);
        return find(connection, value.clientId(), false).orElseThrow();
    }

    public Optional<InstitutionRecord> find(Connection connection, String clientId, boolean lock) throws SQLException {
        String suffix = lock ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM institutions WHERE institution_id=?" + suffix)) {
            statement.setString(1, clientId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new InstitutionRecord(clientId, row.getString("display_name"),
                        row.getString("plugin_name"), row.getString("plugin_main_class"),
                        row.getString("plugin_version"), row.getInt("api_major"),
                        InstitutionRecord.Status.valueOf(row.getString("status")),
                        capabilities(connection, clientId), accounts(connection, clientId),
                        row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant()));
            }
        }
    }

    public void requireOwnedAccount(Connection connection, String clientId, AccountId accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM accounts WHERE account_id=? AND owner_type='PLUGIN'
                    AND owner_id=? AND status='ACTIVE'
                """)) {
            statement.setString(1, accountId.value()); statement.setString(2, clientId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                if (row.getInt(1) != 1) throw new LedgerException(LedgerException.Code.UNAUTHORIZED,
                        "institution account is not owned by caller");
            }
        }
    }

    private void createAccount(Connection connection, EcosystemStore.InstitutionRegistration value, String purpose)
            throws SQLException {
        AccountId id = AccountId.plugin(value.clientId(), purpose);
        try (PreparedStatement account = connection.prepareStatement("""
                INSERT IGNORE INTO accounts(account_id,account_class,owner_type,owner_id,purpose,status,
                    permits_negative,parent_account_id,created_at) VALUES (?,'INSTITUTION','PLUGIN',?,?,'ACTIVE',0,NULL,?)
                """)) {
            account.setString(1, id.value()); account.setString(2, value.clientId());
            account.setString(3, purpose); account.setTimestamp(4, Timestamp.from(value.registeredAt()));
            account.executeUpdate();
        }
        try (PreparedStatement balance = connection.prepareStatement(
                "INSERT IGNORE INTO account_balances(account_id,balance_minor,reserved_minor,version) VALUES (?,0,0,0)")) {
            balance.setString(1, id.value()); balance.executeUpdate();
        }
    }

    private Set<InstitutionCapability> capabilities(Connection connection, String clientId) throws SQLException {
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

    private Map<String, AccountId> accounts(Connection connection, String clientId) throws SQLException {
        Map<String, AccountId> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT purpose,account_id FROM accounts WHERE owner_type='PLUGIN' AND owner_id=? ORDER BY purpose
                """)) {
            statement.setString(1, clientId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.put(rows.getString(1), new AccountId(rows.getString(2)));
            }
        }
        return Map.copyOf(result);
    }
}
