package com.blocke.centraleconomy.storage.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Runtime-owned MySQL pool shared by every Bloeco storage component. */
public final class BloecoDataSource implements AutoCloseable {
    private final HikariDataSource pool;

    public BloecoDataSource(String jdbcUrl, String username, String password, int maximumPoolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(Objects.requireNonNull(jdbcUrl, "jdbcUrl"));
        config.setUsername(Objects.requireNonNull(username, "username"));
        config.setPassword(password == null ? "" : password);
        config.setMaximumPoolSize(Math.max(2, maximumPoolSize));
        config.setMinimumIdle(Math.min(2, Math.max(1, maximumPoolSize)));
        config.setPoolName("Bloeco-MySQL");
        config.setConnectionTimeout(5_000);
        config.setValidationTimeout(2_000);
        config.setMaxLifetime(1_800_000);
        config.setKeepaliveTime(120_000);
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("cachePrepStmts", "true");
        this.pool = new HikariDataSource(config);
    }

    public DataSource dataSource() {
        return pool;
    }

    public Connection connection() throws SQLException {
        Connection connection = pool.getConnection();
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        return connection;
    }

    public boolean isClosed() {
        return pool.isClosed();
    }

    @Override
    public void close() {
        pool.close();
    }
}
