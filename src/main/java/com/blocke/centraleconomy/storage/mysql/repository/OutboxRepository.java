package com.blocke.centraleconomy.storage.mysql.repository;

import com.blocke.centraleconomy.domain.ledger.JournalEntry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

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
}
