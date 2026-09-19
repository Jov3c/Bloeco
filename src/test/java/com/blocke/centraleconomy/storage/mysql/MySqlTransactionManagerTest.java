package com.blocke.centraleconomy.storage.mysql;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlTransactionManagerTest {
    @Test
    void commitsSuccessfulWorkAndReturnsConnection() {
        RecordingDataSource source = new RecordingDataSource();
        MySqlTransactionManager transactions = new MySqlTransactionManager(source);

        String value = transactions.execute(connection -> {
            assertFalse(connection.getAutoCommit());
            return "ok";
        });

        assertEquals("ok", value);
        assertEquals(1, source.commits.get());
        assertEquals(0, source.rollbacks.get());
        assertEquals(1, source.closes.get());
    }

    @Test
    void rollsBackFailedWorkAndReturnsConnection() {
        RecordingDataSource source = new RecordingDataSource();
        MySqlTransactionManager transactions = new MySqlTransactionManager(source);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> transactions.execute(connection -> { throw new IllegalStateException("boom"); }));

        assertEquals("boom", failure.getMessage());
        assertEquals(0, source.commits.get());
        assertEquals(1, source.rollbacks.get());
        assertEquals(1, source.closes.get());
    }

    @Test
    void restoresOriginalAutoCommitBeforeReturningConnection() {
        RecordingDataSource source = new RecordingDataSource();
        MySqlTransactionManager transactions = new MySqlTransactionManager(source);

        transactions.execute(connection -> null);

        assertTrue(source.autoCommit.get());
        assertEquals(2, source.autoCommitChanges.get());
    }

    @Test
    void rollsBackErrorsAndDoesNotLeaveConnectionCheckedOut() {
        RecordingDataSource source = new RecordingDataSource();
        MySqlTransactionManager transactions = new MySqlTransactionManager(source);

        assertThrows(AssertionError.class,
                () -> transactions.execute(connection -> { throw new AssertionError("fatal"); }));

        assertEquals(1, source.rollbacks.get());
        assertEquals(1, source.closes.get());
    }

    private static final class RecordingDataSource implements DataSource {
        private final AtomicBoolean autoCommit = new AtomicBoolean(true);
        private final AtomicInteger autoCommitChanges = new AtomicInteger();
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private final Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getAutoCommit" -> autoCommit.get();
                        case "setTransactionIsolation" -> null;
                        case "setAutoCommit" -> {
                            autoCommit.set((boolean) args[0]);
                            autoCommitChanges.incrementAndGet();
                            yield null;
                        }
                        case "commit" -> { commits.incrementAndGet(); yield null; }
                        case "rollback" -> { rollbacks.incrementAndGet(); yield null; }
                        case "close" -> { closes.incrementAndGet(); yield null; }
                        case "isClosed" -> false;
                        case "unwrap" -> null;
                        case "isWrapperFor" -> false;
                        case "toString" -> "RecordingConnection";
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });

        @Override public Connection getConnection() { return connection; }
        @Override public Connection getConnection(String username, String password) { return connection; }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
