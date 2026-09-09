package com.blocke.centraleconomy.storage.mysql;

import com.blocke.centraleconomy.application.banking.BankingStore;
import com.blocke.centraleconomy.domain.banking.BankSnapshot;
import com.blocke.centraleconomy.domain.banking.BankingPlayerSnapshot;
import com.blocke.centraleconomy.domain.banking.BankingPolicy;
import com.blocke.centraleconomy.domain.banking.BankingReceipt;
import com.blocke.centraleconomy.domain.banking.LoanReceipt;
import com.blocke.centraleconomy.domain.ledger.LedgerException;
import com.blocke.centraleconomy.domain.money.Money;
import com.blocke.centraleconomy.storage.sqlite.SqliteBankingStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Pooled MySQL banking adapter; every call owns one short database transaction. */
public final class MySqlBankingStore implements BankingStore {
    private final HikariDataSource dataSource;
    private final Clock clock;

    public MySqlBankingStore(String jdbcUrl, String username, String password, int maximumPoolSize, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password == null ? "" : password);
        config.setMaximumPoolSize(Math.max(2, maximumPoolSize));
        config.setMinimumIdle(Math.min(2, Math.max(1, maximumPoolSize)));
        config.setPoolName("Bloeco-Banking-MySQL");
        dataSource = new HikariDataSource(config);
        try (Connection connection = dataSource.getConnection()) {
            MySqlSchema.apply(connection);
        } catch (SQLException exception) {
            dataSource.close();
            throw storage(exception);
        }
    }

    @Override public void initialize(Money capital, BankingPolicy defaults) {
        call(store -> { store.initialize(capital, defaults); return null; });
    }
    @Override public BankingReceipt deposit(UUID player, Money amount, String key) {
        return call(store -> store.deposit(player, amount, key));
    }
    @Override public BankingReceipt depositAll(UUID player, String key) {
        return call(store -> store.depositAll(player, key));
    }
    @Override public BankingReceipt withdraw(UUID player, Money amount, String key) {
        return call(store -> store.withdraw(player, amount, key));
    }
    @Override public BankingReceipt withdrawAll(UUID player, String key) {
        return call(store -> store.withdrawAll(player, key));
    }
    @Override public LoanReceipt borrow(UUID player, Money amount, String key) {
        return call(store -> store.borrow(player, amount, key));
    }
    @Override public BankingReceipt repay(UUID player, UUID loan, Money amount, String key) {
        return call(store -> store.repay(player, loan, amount, key));
    }
    @Override public BankingPlayerSnapshot playerSnapshot(UUID player) {
        return call(store -> store.playerSnapshot(player));
    }
    @Override public BankSnapshot bankSnapshot() { return call(SqliteBankingStore::bankSnapshot); }
    @Override public BankingPolicy updatePolicy(BankingPolicy policy, String actor) {
        return call(store -> store.updatePolicy(policy, actor));
    }

    private <T> T call(Work<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try (SqliteBankingStore session = new SqliteBankingStore(connection, clock, true, false)) {
                return work.apply(session);
            }
        } catch (SQLException exception) {
            throw storage(exception);
        }
    }

    @Override public void close() { dataSource.close(); }

    private static LedgerException storage(Exception cause) {
        return new LedgerException(LedgerException.Code.STORAGE_UNAVAILABLE, "MySQL 银行账本暂时不可用", cause);
    }

    @FunctionalInterface private interface Work<T> { T apply(SqliteBankingStore store); }
}
