package com.blocke.centraleconomy.storage.mysql.repository;

import com.blocke.centraleconomy.application.integration.ReservationRecord;
import com.blocke.centraleconomy.domain.money.Money;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** MySQL reservation state persistence. */
public final class ReservationRepository {
    public Optional<ReservationRecord> find(Connection connection, UUID id, boolean lock) throws SQLException {
        return query(connection, "SELECT * FROM ecosystem_reservations WHERE reservation_id=?"+
                (lock?" FOR UPDATE":""), id.toString());
    }
    public Optional<ReservationRecord> findByKey(Connection connection, String client, String key) throws SQLException {
        return query(connection, "SELECT * FROM ecosystem_reservations WHERE institution_id=? AND reserve_idempotency_key=?",
                client,key);
    }
    private Optional<ReservationRecord> query(Connection connection,String sql,String... values) throws SQLException {
        try(PreparedStatement s=connection.prepareStatement(sql)){
            for(int i=0;i<values.length;i++)s.setString(i+1,values[i]);
            try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(read(r)):Optional.empty();}
        }
    }
    public void insert(Connection c, ReservationRecord r) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("""
                INSERT INTO ecosystem_reservations(reservation_id,institution_id,player_uuid,principal_minor,
                tax_minor,tax_rule_version,status,expires_at_epoch_ms,created_at_epoch_ms,updated_at_epoch_ms,
                reserve_idempotency_key,reserve_fingerprint) VALUES(?,?,?,?,?,?,'ACTIVE',?,?,?,?,?)
                """)){
            s.setString(1,r.reservationId().toString());s.setString(2,r.clientId());s.setString(3,r.playerId().toString());
            s.setLong(4,r.principal().minor());s.setLong(5,r.tax().minor());s.setString(6,r.taxRuleVersion().toString());
            s.setLong(7,r.expiresAt().toEpochMilli());s.setLong(8,r.createdAt().toEpochMilli());
            s.setLong(9,r.updatedAt().toEpochMilli());s.setString(10,r.idempotencyKey());s.setString(11,r.fingerprint());
            s.executeUpdate();
        }
    }
    private static ReservationRecord read(ResultSet r)throws SQLException{return new ReservationRecord(
            UUID.fromString(r.getString("reservation_id")),r.getString("institution_id"),
            UUID.fromString(r.getString("player_uuid")),Money.ofMinor(r.getLong("principal_minor")),
            Money.ofMinor(r.getLong("tax_minor")),UUID.fromString(r.getString("tax_rule_version")),
            ReservationRecord.Status.valueOf(r.getString("status")),Instant.ofEpochMilli(r.getLong("expires_at_epoch_ms")),
            Instant.ofEpochMilli(r.getLong("created_at_epoch_ms")),Instant.ofEpochMilli(r.getLong("updated_at_epoch_ms")),
            r.getString("reserve_idempotency_key"),r.getString("reserve_fingerprint"));}
}
