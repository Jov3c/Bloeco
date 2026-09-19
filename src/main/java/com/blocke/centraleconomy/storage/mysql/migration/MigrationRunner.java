package com.blocke.centraleconomy.storage.mysql.migration;

import com.blocke.centraleconomy.storage.mysql.MySqlTransactionManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Applies every missing migration in strict version order and records it in the same transaction. */
public final class MigrationRunner {
    private final DataSource dataSource;
    private final MySqlTransactionManager transactions;
    private final List<Migration> migrations;

    public MigrationRunner(DataSource dataSource, MySqlTransactionManager transactions,
                           List<Migration> migrations) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.migrations = List.copyOf(Objects.requireNonNull(migrations, "migrations"));
        validateDefinitions(this.migrations);
    }

    public void migrate() {
        ensureHistoryTable();
        Set<Integer> applied = appliedVersions();
        validateHistory(applied);
        for (Migration migration : migrations) {
            if (applied.contains(migration.version())) continue;
            transactions.execute(connection -> {
                migration.migrate(connection);
                try (PreparedStatement record = connection.prepareStatement(
                        "INSERT INTO schema_history(version, applied_at) VALUES (?,?)")) {
                    record.setInt(1, migration.version());
                    record.setTimestamp(2, Timestamp.from(Instant.now()));
                    record.executeUpdate();
                }
                return null;
            });
        }
    }

    private void ensureHistoryTable() {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS schema_history (version INT NOT NULL PRIMARY KEY, applied_at TIMESTAMP NOT NULL)");
        } catch (SQLException exception) {
            throw new IllegalStateException("无法读取数据库迁移历史", exception);
        }
    }

    private Set<Integer> appliedVersions() {
        Set<Integer> result = new HashSet<>();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT version FROM schema_history ORDER BY version")) {
            while (rows.next()) result.add(rows.getInt(1));
            return result;
        } catch (SQLException exception) {
            throw new IllegalStateException("无法读取数据库迁移历史", exception);
        }
    }

    private static void validateDefinitions(List<Migration> migrations) {
        int expected = 1;
        for (Migration migration : migrations) {
            if (migration.version() != expected) {
                throw new IllegalArgumentException("迁移定义必须从 V001 开始连续排列，缺少 V" + String.format("%03d", expected));
            }
            expected++;
        }
    }

    private static void validateHistory(Set<Integer> applied) {
        if (applied.isEmpty()) return;
        List<Integer> ordered = new ArrayList<>(applied);
        ordered.sort(Integer::compareTo);
        for (int index = 0; index < ordered.size(); index++) {
            int expected = index + 1;
            if (ordered.get(index) != expected) {
                throw new IllegalStateException("数据库迁移历史存在断档，缺少 V" + String.format("%03d", expected));
            }
        }
    }
}
