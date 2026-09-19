package com.blocke.centraleconomy.storage.mysql.repository;

import com.blocke.centraleconomy.domain.ledger.JournalEntry;
import com.blocke.centraleconomy.domain.ledger.Posting;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/** Immutable journal persistence without transaction lifecycle decisions. */
public final class LedgerRepository {
    public void insertJournal(Connection connection, JournalEntry entry) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO journal_entries(entry_id,journal_type,memo,client_id,idempotency_key,
                    reversal_of_entry_id,created_at_epoch_ms) VALUES (?,?,?,?,?,?,?)
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
                "INSERT INTO postings(entry_id,line_no,account_id,amount_minor) VALUES (?,?,?,?)")) {
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

    public void insertIdempotency(Connection connection, JournalEntry entry) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO idempotency_records(client_id,idempotency_key,entry_id,request_fingerprint)
                VALUES (?,?,?,?)
                """)) {
            statement.setString(1, entry.clientId());
            statement.setString(2, entry.idempotencyKey());
            statement.setString(3, entry.id().toString());
            statement.setString(4, Integer.toHexString(Objects.hash(
                    entry.type(), entry.memo(), entry.reversalOf(), entry.postings())));
            statement.executeUpdate();
        }
    }
}
