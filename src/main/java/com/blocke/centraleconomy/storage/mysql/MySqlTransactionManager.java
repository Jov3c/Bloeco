package com.blocke.centraleconomy.storage.mysql;

import com.blocke.centraleconomy.domain.ledger.LedgerException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Owns the transaction boundary while repositories only execute SQL on the supplied connection. */
public final class MySqlTransactionManager {
    private final DataSource dataSource;

    public MySqlTransactionManager(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public <T> T execute(TransactionWork<T> work) {
        Objects.requireNonNull(work, "work");
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                commit(connection);
                return result;
            } catch (Throwable failure) {
                try {
                    rollback(connection);
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw propagate(failure);
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (LedgerException | IllegalStateException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw storageFailure("MySQL 事务暂时不可用", exception);
        }
    }

    Connection begin() throws SQLException {
        Connection connection = dataSource.getConnection();
        try {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            return connection;
        } catch (SQLException failure) {
            try { connection.close(); }
            catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    void commit(Connection connection) throws SQLException {
        connection.commit();
    }

    void rollback(Connection connection) throws SQLException {
        connection.rollback();
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return storageFailure("MySQL 事务提交失败", failure);
    }

    private static LedgerException storageFailure(String message, Throwable cause) {
        return new LedgerException(LedgerException.Code.STORAGE_UNAVAILABLE, message, cause);
    }
}
