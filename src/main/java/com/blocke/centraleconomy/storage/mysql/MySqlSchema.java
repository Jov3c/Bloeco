package com.blocke.centraleconomy.storage.mysql;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Creates and upgrades the authoritative MySQL/InnoDB ledger schema. */
final class MySqlSchema {
    static final int VERSION = 4;

    private MySqlSchema() {}

    static void apply(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS schema_history (
                        version INT NOT NULL PRIMARY KEY,
                        applied_at DATETIME(3) NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS institutions (
                        institution_id VARCHAR(128) NOT NULL PRIMARY KEY,
                        display_name VARCHAR(255) NOT NULL,
                        plugin_version VARCHAR(64) NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        created_at DATETIME(3) NOT NULL,
                        updated_at DATETIME(3) NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS accounts (
                        account_id VARCHAR(96) NOT NULL PRIMARY KEY,
                        account_class VARCHAR(32) NOT NULL,
                        owner_type VARCHAR(32) NOT NULL,
                        owner_id VARCHAR(128) NOT NULL,
                        purpose VARCHAR(255) NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        permits_negative BOOLEAN NOT NULL,
                        parent_account_id VARCHAR(96) NULL,
                        created_at DATETIME(3) NOT NULL,
                        CONSTRAINT fk_accounts_parent FOREIGN KEY (parent_account_id) REFERENCES accounts(account_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS account_balances (
                        account_id VARCHAR(96) NOT NULL PRIMARY KEY,
                        balance_minor BIGINT NOT NULL,
                        CONSTRAINT fk_balances_account FOREIGN KEY (account_id) REFERENCES accounts(account_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS fund_reservations (
                        reservation_id CHAR(36) NOT NULL PRIMARY KEY,
                        institution_id VARCHAR(128) NOT NULL,
                        account_id VARCHAR(96) NOT NULL,
                        amount_minor BIGINT NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        idempotency_key VARCHAR(255) NOT NULL,
                        created_at DATETIME(3) NOT NULL,
                        released_at DATETIME(3) NULL,
                        UNIQUE KEY uq_reservation_idempotency (institution_id, idempotency_key),
                        CONSTRAINT fk_reservation_institution FOREIGN KEY (institution_id) REFERENCES institutions(institution_id),
                        CONSTRAINT fk_reservation_account FOREIGN KEY (account_id) REFERENCES accounts(account_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS journal_entries (
                        entry_id CHAR(36) NOT NULL PRIMARY KEY,
                        journal_type VARCHAR(32) NOT NULL,
                        memo VARCHAR(1000) NOT NULL,
                        client_id VARCHAR(128) NULL,
                        idempotency_key VARCHAR(255) NULL,
                        reversal_of_entry_id CHAR(36) NULL,
                        created_at_epoch_ms BIGINT NOT NULL,
                        UNIQUE KEY uq_journal_idempotency (client_id, idempotency_key),
                        UNIQUE KEY uq_one_reversal (reversal_of_entry_id),
                        CONSTRAINT fk_journal_reversal FOREIGN KEY (reversal_of_entry_id) REFERENCES journal_entries(entry_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS postings (
                        entry_id CHAR(36) NOT NULL,
                        line_no INT NOT NULL,
                        account_id VARCHAR(96) NOT NULL,
                        amount_minor BIGINT NOT NULL,
                        PRIMARY KEY (entry_id, line_no),
                        CONSTRAINT fk_postings_entry FOREIGN KEY (entry_id) REFERENCES journal_entries(entry_id),
                        CONSTRAINT fk_postings_account FOREIGN KEY (account_id) REFERENCES accounts(account_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS settlements (
                        settlement_id CHAR(36) NOT NULL PRIMARY KEY,
                        institution_id VARCHAR(128) NOT NULL,
                        journal_id CHAR(36) NULL,
                        business_type VARCHAR(128) NOT NULL,
                        business_reference VARCHAR(255) NOT NULL,
                        idempotency_key VARCHAR(255) NOT NULL,
                        display_memo VARCHAR(256) NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        created_at DATETIME(3) NOT NULL,
                        UNIQUE KEY uq_settlement_idempotency (institution_id, idempotency_key),
                        CONSTRAINT fk_settlement_institution FOREIGN KEY (institution_id) REFERENCES institutions(institution_id),
                        CONSTRAINT fk_settlement_journal FOREIGN KEY (journal_id) REFERENCES journal_entries(entry_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS settlement_items (
                        settlement_item_id CHAR(36) NOT NULL PRIMARY KEY,
                        settlement_id CHAR(36) NOT NULL,
                        account_id VARCHAR(96) NOT NULL,
                        item_type VARCHAR(32) NOT NULL,
                        amount_minor BIGINT NOT NULL,
                        tax_minor BIGINT NOT NULL DEFAULT 0,
                        fee_minor BIGINT NOT NULL DEFAULT 0,
                        snapshot_json JSON NOT NULL,
                        CONSTRAINT fk_settlement_item_settlement FOREIGN KEY (settlement_id) REFERENCES settlements(settlement_id),
                        CONSTRAINT fk_settlement_item_account FOREIGN KEY (account_id) REFERENCES accounts(account_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS settlement_batches (
                        batch_id CHAR(36) NOT NULL PRIMARY KEY,
                        institution_id VARCHAR(128) NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        item_count INT NOT NULL,
                        created_at DATETIME(3) NOT NULL,
                        completed_at DATETIME(3) NULL,
                        CONSTRAINT fk_batch_institution FOREIGN KEY (institution_id) REFERENCES institutions(institution_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS idempotency_records (
                        client_id VARCHAR(128) NOT NULL,
                        idempotency_key VARCHAR(255) NOT NULL,
                        entry_id CHAR(36) NOT NULL UNIQUE,
                        request_fingerprint VARCHAR(128) NOT NULL,
                        PRIMARY KEY (client_id, idempotency_key),
                        CONSTRAINT fk_idempotency_entry FOREIGN KEY (entry_id) REFERENCES journal_entries(entry_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS tax_rules (
                        version_id CHAR(36) NOT NULL PRIMARY KEY,
                        category VARCHAR(32) NOT NULL,
                        basis_points INT NOT NULL,
                        fixed_minor BIGINT NOT NULL,
                        destination_account_id VARCHAR(96) NOT NULL,
                        effective_from_epoch_ms BIGINT NOT NULL,
                        effective_until_epoch_ms BIGINT NULL,
                        actor_id VARCHAR(128) NOT NULL,
                        memo VARCHAR(1000) NOT NULL,
                        CONSTRAINT fk_tax_destination FOREIGN KEY (destination_account_id) REFERENCES accounts(account_id),
                        CHECK (basis_points BETWEEN 0 AND 10000),
                        CHECK (fixed_minor >= 0)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS issuance_requests (
                        request_id CHAR(36) NOT NULL PRIMARY KEY,
                        amount_minor BIGINT NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        reason VARCHAR(1000) NOT NULL,
                        requester_id VARCHAR(128) NOT NULL,
                        approver_id VARCHAR(128) NULL,
                        requested_at_epoch_ms BIGINT NOT NULL,
                        approved_at_epoch_ms BIGINT NULL,
                        executed_entry_id CHAR(36) NULL,
                        CONSTRAINT fk_issuance_entry FOREIGN KEY (executed_entry_id) REFERENCES journal_entries(entry_id),
                        CHECK (amount_minor > 0)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_settlements (
                        entry_id CHAR(36) NOT NULL PRIMARY KEY,
                        sender_id CHAR(36) NOT NULL,
                        recipient_id CHAR(36) NOT NULL,
                        principal_minor BIGINT NOT NULL,
                        sender_debit_minor BIGINT NOT NULL,
                        recipient_net_minor BIGINT NOT NULL,
                        fee_minor BIGINT NOT NULL,
                        income_tax_minor BIGINT NOT NULL,
                        fee_rule_version CHAR(36) NOT NULL,
                        income_rule_version CHAR(36) NOT NULL,
                        memo VARCHAR(1000) NOT NULL,
                        CONSTRAINT fk_settlement_entry FOREIGN KEY (entry_id) REFERENCES journal_entries(entry_id),
                        CONSTRAINT fk_settlement_fee_rule FOREIGN KEY (fee_rule_version) REFERENCES tax_rules(version_id),
                        CONSTRAINT fk_settlement_income_rule FOREIGN KEY (income_rule_version) REFERENCES tax_rules(version_id),
                        CHECK (principal_minor > 0), CHECK (sender_debit_minor > 0),
                        CHECK (recipient_net_minor >= 0), CHECK (fee_minor >= 0), CHECK (income_tax_minor >= 0)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS policy_limits (
                        policy_key VARCHAR(128) NOT NULL PRIMARY KEY,
                        value_minor BIGINT NOT NULL,
                        actor_id VARCHAR(128) NOT NULL,
                        memo VARCHAR(1000) NOT NULL,
                        updated_at_epoch_ms BIGINT NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS audit_events (
                        event_id CHAR(36) NOT NULL PRIMARY KEY,
                        actor_id VARCHAR(128) NOT NULL,
                        action VARCHAR(128) NOT NULL,
                        memo VARCHAR(1000) NOT NULL,
                        entry_id CHAR(36) NULL,
                        created_at_epoch_ms BIGINT NOT NULL,
                        CONSTRAINT fk_audit_entry FOREIGN KEY (entry_id) REFERENCES journal_entries(entry_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS daily_monetary_metrics (
                        metric_date DATE NOT NULL PRIMARY KEY,
                        issued_minor BIGINT NOT NULL,
                        retired_minor BIGINT NOT NULL,
                        transfer_minor BIGINT NOT NULL,
                        tax_minor BIGINT NOT NULL,
                        fee_minor BIGINT NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS outbox_events (
                        event_id CHAR(36) NOT NULL PRIMARY KEY,
                        event_type VARCHAR(128) NOT NULL,
                        aggregate_id CHAR(36) NULL,
                        payload JSON NOT NULL,
                        stream_key VARCHAR(255) NOT NULL,
                        published_at DATETIME(3) NULL,
                        attempts INT NOT NULL DEFAULT 0,
                        created_at DATETIME(3) NOT NULL,
                        INDEX ix_outbox_pending (published_at, created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS banks (
                        bank_id VARCHAR(128) NOT NULL PRIMARY KEY,
                        display_name VARCHAR(255) NOT NULL,
                        cash_account_id VARCHAR(96) NOT NULL,
                        deposit_rate_bps INT NOT NULL,
                        loan_rate_bps INT NOT NULL,
                        reserve_ratio_bps INT NOT NULL,
                        maximum_loan_minor BIGINT NOT NULL,
                        lending_enabled BOOLEAN NOT NULL,
                        loan_term_days INT NOT NULL,
                        created_at_epoch_ms BIGINT NOT NULL,
                        updated_at_epoch_ms BIGINT NOT NULL,
                        CONSTRAINT fk_bank_cash FOREIGN KEY (cash_account_id) REFERENCES accounts(account_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS bank_deposits (
                        bank_id VARCHAR(128) NOT NULL,
                        player_uuid CHAR(36) NOT NULL,
                        principal_minor BIGINT NOT NULL,
                        accrued_interest_minor BIGINT NOT NULL DEFAULT 0,
                        last_interest_epoch_ms BIGINT NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        updated_at_epoch_ms BIGINT NOT NULL,
                        PRIMARY KEY(bank_id, player_uuid),
                        CONSTRAINT fk_deposit_bank FOREIGN KEY (bank_id) REFERENCES banks(bank_id),
                        CHECK (principal_minor >= 0), CHECK (accrued_interest_minor >= 0)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS bank_loans (
                        loan_id CHAR(36) NOT NULL PRIMARY KEY,
                        bank_id VARCHAR(128) NOT NULL,
                        borrower_uuid CHAR(36) NOT NULL,
                        original_principal_minor BIGINT NOT NULL,
                        outstanding_principal_minor BIGINT NOT NULL,
                        outstanding_interest_minor BIGINT NOT NULL,
                        interest_rate_bps INT NOT NULL,
                        issued_at_epoch_ms BIGINT NOT NULL,
                        due_at_epoch_ms BIGINT NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        disbursement_journal_id CHAR(36) NOT NULL,
                        updated_at_epoch_ms BIGINT NOT NULL,
                        INDEX ix_loans_borrower_status (borrower_uuid, status),
                        CONSTRAINT fk_loan_bank FOREIGN KEY (bank_id) REFERENCES banks(bank_id),
                        CONSTRAINT fk_loan_journal FOREIGN KEY (disbursement_journal_id) REFERENCES journal_entries(entry_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS bank_loan_payments (
                        payment_id CHAR(36) NOT NULL PRIMARY KEY,
                        loan_id CHAR(36) NOT NULL,
                        principal_minor BIGINT NOT NULL,
                        interest_minor BIGINT NOT NULL,
                        journal_id CHAR(36) NOT NULL,
                        paid_at_epoch_ms BIGINT NOT NULL,
                        CONSTRAINT fk_payment_loan FOREIGN KEY (loan_id) REFERENCES bank_loans(loan_id),
                        CONSTRAINT fk_payment_journal FOREIGN KEY (journal_id) REFERENCES journal_entries(entry_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS bank_operations (
                        operation_id CHAR(36) NOT NULL PRIMARY KEY,
                        idempotency_key VARCHAR(255) NOT NULL UNIQUE,
                        operation_type VARCHAR(32) NOT NULL,
                        player_uuid CHAR(36) NULL,
                        amount_minor BIGINT NOT NULL,
                        loan_id CHAR(36) NULL,
                        journal_id CHAR(36) NOT NULL,
                        created_at_epoch_ms BIGINT NOT NULL,
                        CONSTRAINT fk_operation_loan FOREIGN KEY (loan_id) REFERENCES bank_loans(loan_id),
                        CONSTRAINT fk_operation_journal FOREIGN KEY (journal_id) REFERENCES journal_entries(entry_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """);
            statement.executeUpdate("INSERT IGNORE INTO schema_history(version, applied_at) VALUES ("
                    + VERSION + ", UTC_TIMESTAMP(3))");
        }
    }
}
