package com.blocke.centraleconomy.storage.mysql.repository;

import com.blocke.centraleconomy.application.PlayerSettlement;
import com.blocke.centraleconomy.cluster.ClusterEventCodec;
import com.blocke.centraleconomy.cluster.PlayerNotification;
import com.blocke.centraleconomy.domain.ledger.JournalEntry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.time.Instant;

/** Transactional outbox writes. Publication remains at-least-once and uses event_id for deduplication. */
public final class OutboxRepository {
    public void insertJournalCommitted(Connection connection, JournalEntry entry) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO outbox_events(event_id,event_type,aggregate_id,payload,stream_key,created_at)
                VALUES (?, 'JOURNAL_COMMITTED', ?, ?, 'bloeco:v2:ledger-events', UTC_TIMESTAMP(3))
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, entry.id().toString());
            statement.setString(3, "{\"journal_id\":\"" + entry.id() + "\",\"type\":\""
                    + entry.type().name() + "\"}");
            statement.executeUpdate();
        }
    }

    public void insertPlayerNotification(Connection connection, JournalEntry entry,
                                         PlayerSettlement settlement) throws SQLException {
        UUID notificationId = entry.id();
        PlayerNotification notification = new PlayerNotification(notificationId, entry.id(),
                settlement.recipientId(), settlement.recipientNet().minor(), entry.createdAt());
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO player_notifications(notification_id,entry_id,player_uuid,amount_minor,created_at_epoch_ms)
                VALUES (?,?,?,?,?)
                """)) {
            statement.setString(1, notificationId.toString());
            statement.setString(2, entry.id().toString());
            statement.setString(3, settlement.recipientId().toString());
            statement.setLong(4, settlement.recipientNet().minor());
            statement.setLong(5, entry.createdAt().toEpochMilli());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO outbox_events(event_id,event_type,aggregate_id,payload,stream_key,created_at)
                VALUES (?, 'PLAYER_NOTIFICATION', ?, ?, 'bloeco:v2:ledger-events', UTC_TIMESTAMP(3))
                """)) {
            statement.setString(1, notificationId.toString());
            statement.setString(2, entry.id().toString());
            statement.setString(3, ClusterEventCodec.encode(notification));
            statement.executeUpdate();
        }
    }
}
