package com.blocke.centraleconomy.storage.mysql;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/** Supplies one thread-bound transaction connection or a short connection for an isolated read. */
final class ConnectionScope {
    private final DataSource dataSource;
    private final ThreadLocal<Connection> transaction = new ThreadLocal<>();

    ConnectionScope(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    void bind(Connection connection) {
        if (transaction.get() != null) throw new IllegalStateException("nested MySQL transaction is not supported");
        transaction.set(connection);
    }

    Connection unbind() {
        Connection connection = transaction.get();
        transaction.remove();
        return connection;
    }

    Connection currentTransaction() {
        Connection current = transaction.get();
        if (current == null) throw new IllegalStateException("no active MySQL transaction");
        return current;
    }

    PreparedStatement prepareStatement(String sql) throws SQLException {
        Connection active = transaction.get();
        if (active != null) return active.prepareStatement(sql);
        Connection shortConnection = openReadConnection();
        try {
            PreparedStatement statement = shortConnection.prepareStatement(sql);
            return closeConnectionWith(statement, PreparedStatement.class, shortConnection);
        } catch (SQLException failure) {
            shortConnection.close();
            throw failure;
        }
    }

    Statement createStatement() throws SQLException {
        Connection active = transaction.get();
        if (active != null) return active.createStatement();
        Connection shortConnection = openReadConnection();
        try {
            Statement statement = shortConnection.createStatement();
            return closeConnectionWith(statement, Statement.class, shortConnection);
        } catch (SQLException failure) {
            shortConnection.close();
            throw failure;
        }
    }

    private Connection openReadConnection() throws SQLException {
        Connection connection = dataSource.getConnection();
        try {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setReadOnly(true);
            return connection;
        } catch (SQLException failure) {
            try { connection.close(); }
            catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Statement> T closeConnectionWith(T delegate, Class<T> type,
                                                                Connection connection) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            try {
                if (method.getName().equals("close")) {
                    try { delegate.close(); } finally { connection.close(); }
                    return null;
                }
                return method.invoke(delegate, args);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        });
    }
}
