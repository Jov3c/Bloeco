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
    public static final int LATEST_VERSION = 6;

    private MySqlMigrations() { }

    public static void migrate(DataSource dataSource, MySqlTransactionManager transactions) {
        bridgeLegacyHistory(dataSource, transactions);
        new MigrationRunner(dataSource, transactions, List.of(
                new V001InitialSchema(),
                new V002Banking(),
                new V003Outbox(),
                new V004Indexes(),
                new V005RuntimeIndexes(),
                new V006BalanceVersion())).migrate();
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
}
