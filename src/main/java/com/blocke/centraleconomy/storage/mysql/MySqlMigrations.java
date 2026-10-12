package com.blocke.centraleconomy.storage.mysql;

import com.blocke.centraleconomy.storage.mysql.migration.Migration;
import com.blocke.centraleconomy.storage.mysql.migration.MigrationRunner;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Fixed MySQL migration chain. New schema changes must append a version and never edit history. */
public final class MySqlMigrations {
    public static final int LATEST_VERSION = 7;

    private MySqlMigrations() { }

    public static void migrate(DataSource dataSource, MySqlTransactionManager transactions) {
        bridgeLegacyHistory(dataSource, transactions);
        new MigrationRunner(dataSource, transactions, List.of(
                new V001InitialSchema(),
                new V002Banking(),
                new V003Outbox(),
                new V004Indexes(),
                new V005RuntimeIndexes(),
                new V006BalanceVersion(),
                new V007EcosystemApi())).migrate();
    }

    private static void bridgeLegacyHistory(DataSource source, MySqlTransactionManager transactions) {
        int legacyMaximum;
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS schema_history (
                        version INT NOT NULL PRIMARY KEY,
                        applied_at DATETIME(3) NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """);
            try (ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*), COALESCE(MIN(version),0), COALESCE(MAX(version),0) FROM schema_history")) {
                if (!rows.next() || rows.getInt(1) == 0 || rows.getInt(2) == 1 || rows.getInt(3) > 5) return;
                legacyMaximum = rows.getInt(3);
            }
            try (ResultSet tables = connection.getMetaData().getTables(
                    connection.getCatalog(), null, "account_balances", new String[]{"TABLE"})) {
                if (!tables.next()) return;
            }
        } catch (Exception exception) {
            throw new IllegalStateException("无法检查旧版数据库迁移历史", exception);
        }
        transactions.execute(connection -> {
            try (PreparedStatement record = connection.prepareStatement(
                    "INSERT IGNORE INTO schema_history(version, applied_at) VALUES (?,?)")) {
                for (int version = 1; version <= legacyMaximum; version++) {
                    record.setInt(1, version);
                    record.setTimestamp(2, Timestamp.from(Instant.now()));
                    record.addBatch();
                }
                record.executeBatch();
            }
            return null;
        });
    }

    private abstract static class Checkpoint implements Migration {
        private final int version;
        private final String description;
        private Checkpoint(int version, String description) {
            this.version = version;
            this.description = description;
        }
        @Override public int version() { return version; }
        @Override public String description() { return description; }
        @Override public void migrate(Connection connection) { /* Historical checkpoint from the v1 schema. */ }
    }

    private static final class V001InitialSchema implements Migration {
        @Override public int version() { return 1; }
        @Override public String description() { return "initial_schema"; }
        @Override public void migrate(Connection connection) throws Exception { MySqlSchema.apply(connection); }
    }
    private static final class V002Banking extends Checkpoint {
        private V002Banking() { super(2, "banking"); }
    }
    private static final class V003Outbox extends Checkpoint {
        private V003Outbox() { super(3, "outbox"); }
    }
    private static final class V004Indexes extends Checkpoint {
        private V004Indexes() { super(4, "indexes"); }
    }
    private static final class V005RuntimeIndexes extends Checkpoint {
        private V005RuntimeIndexes() { super(5, "runtime_indexes"); }
    }
    private static final class V006BalanceVersion implements Migration {
        @Override public int version() { return 6; }
        @Override public String description() { return "balance_version"; }
        @Override public void migrate(Connection connection) throws Exception {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null,
                    "account_balances", "version")) {
                if (columns.next()) return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE account_balances ADD COLUMN version BIGINT NOT NULL DEFAULT 0");
            }
        }
    }

    private static final class V007EcosystemApi implements Migration {
        @Override public int version() { return 7; }
        @Override public String description() { return "ecosystem_api"; }
        @Override public void migrate(Connection connection) throws Exception {
            addColumnIfMissing(connection, "account_balances", "reserved_minor",
                    "ALTER TABLE account_balances ADD COLUMN reserved_minor BIGINT NOT NULL DEFAULT 0");
            addColumnIfMissing(connection, "institutions", "plugin_name",
                    "ALTER TABLE institutions ADD COLUMN plugin_name VARCHAR(128) NOT NULL DEFAULT 'legacy'");
            addColumnIfMissing(connection, "institutions", "plugin_main_class",
                    "ALTER TABLE institutions ADD COLUMN plugin_main_class VARCHAR(255) NOT NULL DEFAULT 'legacy'");
            addColumnIfMissing(connection, "institutions", "api_major",
                    "ALTER TABLE institutions ADD COLUMN api_major INT NOT NULL DEFAULT 1");
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS institution_capabilities (
                            institution_id VARCHAR(128) NOT NULL,
                            capability VARCHAR(64) NOT NULL,
                            PRIMARY KEY(institution_id, capability),
                            CONSTRAINT fk_capability_institution FOREIGN KEY(institution_id)
                                REFERENCES institutions(institution_id)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS ecosystem_operations (
                            operation_id CHAR(36) NOT NULL PRIMARY KEY,
                            institution_id VARCHAR(128) NOT NULL,
                            idempotency_key VARCHAR(255) NOT NULL,
                            request_fingerprint VARCHAR(128) NOT NULL,
                            operation_type VARCHAR(32) NOT NULL,
                            settlement_kind VARCHAR(32) NOT NULL,
                            player_uuid CHAR(36) NOT NULL,
                            institution_account_id VARCHAR(96) NOT NULL,
                            principal_minor BIGINT NOT NULL,
                            tax_minor BIGINT NOT NULL,
                            fee_minor BIGINT NOT NULL,
                            institution_net_minor BIGINT NOT NULL,
                            tax_rule_version CHAR(36) NULL,
                            business_type VARCHAR(128) NOT NULL,
                            business_reference VARCHAR(255) NOT NULL,
                            display_memo VARCHAR(256) NOT NULL,
                            journal_id CHAR(36) NOT NULL,
                            original_operation_id CHAR(36) NULL,
                            committed_at_epoch_ms BIGINT NOT NULL,
                            UNIQUE KEY uq_ecosystem_operation_key(institution_id,idempotency_key),
                            INDEX ix_ecosystem_account_time(institution_account_id,committed_at_epoch_ms),
                            INDEX ix_ecosystem_original(original_operation_id),
                            CONSTRAINT fk_ecosystem_institution FOREIGN KEY(institution_id) REFERENCES institutions(institution_id),
                            CONSTRAINT fk_ecosystem_account FOREIGN KEY(institution_account_id) REFERENCES accounts(account_id),
                            CONSTRAINT fk_ecosystem_journal FOREIGN KEY(journal_id) REFERENCES journal_entries(entry_id),
                            CONSTRAINT fk_ecosystem_original FOREIGN KEY(original_operation_id) REFERENCES ecosystem_operations(operation_id),
                            CONSTRAINT fk_ecosystem_tax_rule FOREIGN KEY(tax_rule_version) REFERENCES tax_rules(version_id)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS ecosystem_reservations (
                            reservation_id CHAR(36) NOT NULL PRIMARY KEY,
                            institution_id VARCHAR(128) NOT NULL,
                            player_uuid CHAR(36) NOT NULL,
                            principal_minor BIGINT NOT NULL,
                            tax_minor BIGINT NOT NULL,
                            tax_rule_version CHAR(36) NOT NULL,
                            status VARCHAR(32) NOT NULL,
                            expires_at_epoch_ms BIGINT NOT NULL,
                            created_at_epoch_ms BIGINT NOT NULL,
                            updated_at_epoch_ms BIGINT NOT NULL,
                            reserve_idempotency_key VARCHAR(255) NOT NULL,
                            reserve_fingerprint VARCHAR(128) NOT NULL,
                            release_idempotency_key VARCHAR(255) NULL,
                            release_fingerprint VARCHAR(128) NULL,
                            capture_operation_id CHAR(36) NULL,
                            UNIQUE KEY uq_ecosystem_reserve_key(institution_id,reserve_idempotency_key),
                            UNIQUE KEY uq_ecosystem_release_key(institution_id,release_idempotency_key),
                            CONSTRAINT fk_ereservation_institution FOREIGN KEY(institution_id) REFERENCES institutions(institution_id),
                            CONSTRAINT fk_ereservation_tax_rule FOREIGN KEY(tax_rule_version) REFERENCES tax_rules(version_id),
                            CONSTRAINT fk_ereservation_capture FOREIGN KEY(capture_operation_id) REFERENCES ecosystem_operations(operation_id)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                        """);
            }
        }

        private static void addColumnIfMissing(
                Connection connection, String table, String column, String sql) throws Exception {
            try (ResultSet columns = connection.getMetaData().getColumns(
                    connection.getCatalog(), null, table, column)) {
                if (columns.next()) return;
            }
            try (Statement statement = connection.createStatement()) { statement.executeUpdate(sql); }
        }
    }
}
