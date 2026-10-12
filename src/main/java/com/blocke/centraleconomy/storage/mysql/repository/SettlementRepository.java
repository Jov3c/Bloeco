package com.blocke.centraleconomy.storage.mysql.repository;

import com.blocke.bloeco.api.SettlementKind;
import com.blocke.centraleconomy.application.integration.SettlementRecord;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.money.Money;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** MySQL settlement receipt persistence. */
public final class SettlementRepository {
    public Optional<SettlementRecord> findByKey(Connection connection, String clientId, String key)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM ecosystem_operations WHERE institution_id=? AND idempotency_key=?")) {
            statement.setString(1, clientId); statement.setString(2, key);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(read(row)) : Optional.empty();
            }
        }
    }

    public Optional<SettlementRecord> findById(Connection connection, UUID id, boolean lock) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM ecosystem_operations WHERE operation_id=?" + (lock ? " FOR UPDATE" : ""))) {
            statement.setString(1, id.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(read(row)) : Optional.empty();
            }
        }
    }

    public List<SettlementRecord> recent(Connection connection, String clientId, AccountId account, int limit)
            throws SQLException {
        List<SettlementRecord> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM ecosystem_operations WHERE institution_id=? AND institution_account_id=?
                ORDER BY committed_at_epoch_ms DESC,operation_id DESC LIMIT ?
                """)) {
            statement.setString(1, clientId); statement.setString(2, account.value()); statement.setInt(3, limit);
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) result.add(read(rows)); }
        }
        return List.copyOf(result);
    }

    public void insert(Connection connection, SettlementRecord record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ecosystem_operations(operation_id,institution_id,idempotency_key,request_fingerprint,
                    operation_type,settlement_kind,player_uuid,institution_account_id,principal_minor,tax_minor,
                    fee_minor,institution_net_minor,tax_rule_version,business_type,business_reference,display_memo,
                    journal_id,original_operation_id,committed_at_epoch_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            int i=0;
            statement.setString(++i, record.operationId().toString()); statement.setString(++i, record.clientId());
            statement.setString(++i, record.idempotencyKey()); statement.setString(++i, record.fingerprint());
            statement.setString(++i, record.type().name()); statement.setString(++i, record.kind().name());
            statement.setString(++i, record.playerId().toString());
            statement.setString(++i, record.institutionAccountId().value());
            statement.setLong(++i, record.principal().minor()); statement.setLong(++i, record.tax().minor());
            statement.setLong(++i, record.fee().minor()); statement.setLong(++i, record.institutionNet().minor());
            statement.setString(++i, record.taxRuleVersion()==null?null:record.taxRuleVersion().toString());
            statement.setString(++i, record.businessType()); statement.setString(++i, record.businessReference());
            statement.setString(++i, record.displayMemo()); statement.setString(++i, record.journalId().toString());
            statement.setString(++i, record.originalOperationId()==null?null:record.originalOperationId().toString());
            statement.setLong(++i, record.committedAt().toEpochMilli()); statement.executeUpdate();
        }
    }

    public long refunded(Connection connection, UUID original, String column) throws SQLException {
        if (!column.equals("principal_minor") && !column.equals("tax_minor")) throw new IllegalArgumentException();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(SUM("+column+"),0) FROM ecosystem_operations WHERE original_operation_id=? AND operation_type='REFUND'")) {
            statement.setString(1, original.toString());
            try (ResultSet row = statement.executeQuery()) { row.next(); return row.getLong(1); }
        }
    }

    private static SettlementRecord read(ResultSet row) throws SQLException {
        String original=row.getString("original_operation_id"), rule=row.getString("tax_rule_version");
        return new SettlementRecord(UUID.fromString(row.getString("operation_id")),
                UUID.fromString(row.getString("journal_id")), row.getString("institution_id"),
                SettlementRecord.Type.valueOf(row.getString("operation_type")),
                SettlementKind.valueOf(row.getString("settlement_kind")),
                UUID.fromString(row.getString("player_uuid")), new AccountId(row.getString("institution_account_id")),
                Money.ofMinor(row.getLong("principal_minor")), Money.ofMinor(row.getLong("tax_minor")),
                Money.ofMinor(row.getLong("fee_minor")), Money.ofMinor(row.getLong("institution_net_minor")),
                row.getString("business_type"), row.getString("business_reference"), row.getString("display_memo"),
                row.getString("idempotency_key"), row.getString("request_fingerprint"),
                Instant.ofEpochMilli(row.getLong("committed_at_epoch_ms")),
                original==null?null:UUID.fromString(original), rule==null?null:UUID.fromString(rule));
    }
}
