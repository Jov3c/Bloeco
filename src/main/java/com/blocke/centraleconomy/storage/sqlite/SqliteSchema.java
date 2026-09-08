package com.blocke.centraleconomy.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Creates the version 2 central-journal schema on a new SQLite database. */
final class SqliteSchema {
    static final int VERSION = 2;

    private SqliteSchema() {}

    static void apply(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS schema_history (
                        version INTEGER PRIMARY KEY,
                        applied_at_epoch_ms INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS accounts (
                        account_id TEXT PRIMARY KEY,
                        account_class TEXT NOT NULL,
                        owner_type TEXT NOT NULL,
                        owner_id TEXT NOT NULL,
                        purpose TEXT NOT NULL,
                        status TEXT NOT NULL,
                        permits_negative INTEGER NOT NULL CHECK(permits_negative IN (0, 1)),
                        parent_account_id TEXT,
                        created_at_epoch_ms INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS account_balances (
                        account_id TEXT PRIMARY KEY REFERENCES accounts(account_id),
                        balance_minor INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS journal_entries (
                        entry_id TEXT PRIMARY KEY,
                        journal_type TEXT NOT NULL,
                        memo TEXT NOT NULL,
                        client_id TEXT,
                        idempotency_key TEXT,
                        reversal_of_entry_id TEXT REFERENCES journal_entries(entry_id),
                        created_at_epoch_ms INTEGER NOT NULL,
                        UNIQUE(client_id, idempotency_key)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS postings (
                        entry_id TEXT NOT NULL REFERENCES journal_entries(entry_id),
                        line_no INTEGER NOT NULL,
                        account_id TEXT NOT NULL REFERENCES accounts(account_id),
                        amount_minor INTEGER NOT NULL CHECK(amount_minor <> 0),
                        PRIMARY KEY(entry_id, line_no)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS postings_by_account
                    ON postings(account_id, entry_id)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS idempotency_records (
                        client_id TEXT NOT NULL,
                        idempotency_key TEXT NOT NULL,
                        entry_id TEXT NOT NULL UNIQUE REFERENCES journal_entries(entry_id),
                        request_fingerprint TEXT NOT NULL,
                        PRIMARY KEY(client_id, idempotency_key)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS tax_rules (
                        version_id TEXT PRIMARY KEY,
                        category TEXT NOT NULL,
                        basis_points INTEGER NOT NULL CHECK(basis_points BETWEEN 0 AND 10000),
                        fixed_minor INTEGER NOT NULL CHECK(fixed_minor >= 0),
                        destination_account_id TEXT NOT NULL REFERENCES accounts(account_id),
                        effective_from_epoch_ms INTEGER NOT NULL,
                        effective_until_epoch_ms INTEGER,
                        actor_id TEXT NOT NULL,
                        memo TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS issuance_requests (
                        request_id TEXT PRIMARY KEY,
                        amount_minor INTEGER NOT NULL CHECK(amount_minor > 0),
                        status TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        requester_id TEXT NOT NULL,
                        approver_id TEXT,
                        requested_at_epoch_ms INTEGER NOT NULL,
                        approved_at_epoch_ms INTEGER,
                        executed_entry_id TEXT REFERENCES journal_entries(entry_id)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS policy_limits (
                        policy_key TEXT PRIMARY KEY,
                        value_minor INTEGER NOT NULL,
                        actor_id TEXT NOT NULL,
                        memo TEXT NOT NULL,
                        updated_at_epoch_ms INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS audit_events (
                        event_id TEXT PRIMARY KEY,
                        actor_id TEXT NOT NULL,
                        action TEXT NOT NULL,
                        memo TEXT NOT NULL,
                        entry_id TEXT REFERENCES journal_entries(entry_id),
                        created_at_epoch_ms INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS daily_monetary_metrics (
                        metric_date TEXT PRIMARY KEY,
                        issued_minor INTEGER NOT NULL,
                        retired_minor INTEGER NOT NULL,
                        transfer_minor INTEGER NOT NULL,
                        tax_minor INTEGER NOT NULL,
                        fee_minor INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("INSERT OR IGNORE INTO schema_history(version, applied_at_epoch_ms) VALUES ("
                    + VERSION + ", " + System.currentTimeMillis() + ")");
        }
    }
}
