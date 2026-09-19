package com.blocke.centraleconomy.storage.mysql;

import com.blocke.centraleconomy.storage.mysql.migration.Migration;
import com.blocke.centraleconomy.storage.mysql.migration.MigrationRunner;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MigrationRunnerTest {
    @Test
    void emptyDatabaseRunsEveryMigrationOnce() throws Exception {
        SQLiteDataSource source = memorySource("empty");
        List<Integer> applied = new ArrayList<>();
        List<Migration> migrations = migrations(1, 6, applied);

        new MigrationRunner(source, new MySqlTransactionManager(source), migrations).migrate();
        new MigrationRunner(source, new MySqlTransactionManager(source), migrations).migrate();

        assertEquals(List.of(1, 2, 3, 4, 5, 6), applied);
        assertEquals(6, currentVersion(source));
    }

    @Test
    void upgradesVersionFourToFiveAndSix() throws Exception {
        SQLiteDataSource source = memorySource("v4");
        seedHistory(source, 4);
        List<Integer> applied = new ArrayList<>();

        new MigrationRunner(source, new MySqlTransactionManager(source), migrations(1, 6, applied)).migrate();

        assertEquals(List.of(5, 6), applied);
        assertEquals(6, currentVersion(source));
    }

    @Test
    void upgradesVersionFiveToSix() throws Exception {
        SQLiteDataSource source = memorySource("v5");
        seedHistory(source, 5);
        List<Integer> applied = new ArrayList<>();

        new MigrationRunner(source, new MySqlTransactionManager(source), migrations(1, 6, applied)).migrate();

        assertEquals(List.of(6), applied);
    }

    @Test
    void migrationFailureRollsBackHistoryAndStopsStartup() throws Exception {
        SQLiteDataSource source = memorySource("failure");
        List<Migration> migrations = List.of(
                migration(1, ignored -> { }),
                migration(2, ignored -> { throw new IllegalStateException("broken migration"); }),
                migration(3, ignored -> { }));

        assertThrows(IllegalStateException.class,
                () -> new MigrationRunner(source, new MySqlTransactionManager(source), migrations).migrate());
        assertEquals(1, currentVersion(source));
    }

    @Test
    void rejectsGapsInRecordedHistory() throws Exception {
        SQLiteDataSource source = memorySource("gap");
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE schema_history(version INTEGER PRIMARY KEY, applied_at TIMESTAMP NOT NULL)");
            statement.executeUpdate("INSERT INTO schema_history VALUES (1,CURRENT_TIMESTAMP)");
            statement.executeUpdate("INSERT INTO schema_history VALUES (3,CURRENT_TIMESTAMP)");
        }

        assertThrows(IllegalStateException.class,
                () -> new MigrationRunner(source, new MySqlTransactionManager(source), migrations(1, 3, new ArrayList<>())).migrate());
    }

    private static List<Migration> migrations(int first, int last, List<Integer> applied) {
        List<Migration> result = new ArrayList<>();
        for (int version = first; version <= last; version++) {
            int current = version;
            result.add(migration(current, ignored -> applied.add(current)));
        }
        return result;
    }

    private static Migration migration(int version, MigrationAction action) {
        return new Migration() {
            @Override public int version() { return version; }
            @Override public String description() { return "v" + version; }
            @Override public void migrate(Connection connection) throws Exception { action.run(connection); }
        };
    }

    private static SQLiteDataSource memorySource(String name) {
        SQLiteDataSource source = new SQLiteDataSource();
        try {
            var file = Files.createTempFile("bloeco-migration-" + name, ".db");
            file.toFile().deleteOnExit();
            source.setUrl("jdbc:sqlite:" + file);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return source;
    }

    private static void seedHistory(SQLiteDataSource source, int version) throws Exception {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE schema_history(version INTEGER PRIMARY KEY, applied_at TIMESTAMP NOT NULL)");
            for (int current = 1; current <= version; current++) {
                statement.executeUpdate("INSERT INTO schema_history VALUES (" + current + ",CURRENT_TIMESTAMP)");
            }
        }
    }

    private static int currentVersion(SQLiteDataSource source) throws Exception {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_history")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    @FunctionalInterface private interface MigrationAction { void run(Connection connection) throws Exception; }
}
