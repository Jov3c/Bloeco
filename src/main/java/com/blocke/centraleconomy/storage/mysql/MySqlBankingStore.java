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
import com.blocke.centraleconomy.storage.mysql.repository.BankRepository;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Pooled MySQL banking adapter; every call owns one short database transaction. */
public final class MySqlBankingStore implements BankingStore {
    private final DataSource dataSource;
    private final MySqlTransactionManager transactions;
    private final AutoCloseable ownedDataSource;
    private final BankRepository bankRepository;

    public MySqlBankingStore(String jdbcUrl, String username, String password, int maximumPoolSize, Clock clock) {
        this(new BloecoDataSource(jdbcUrl, username, password, maximumPoolSize), clock);
    }

    private MySqlBankingStore(BloecoDataSource ownedDataSource, Clock clock) {
        this(ownedDataSource, new MySqlTransactionManager(ownedDataSource.dataSource()), clock, ownedDataSource);
    }

    public MySqlBankingStore(BloecoDataSource sharedDataSource, MySqlTransactionManager transactions, Clock clock) {
        this(sharedDataSource, transactions, clock, null);
    }

    private MySqlBankingStore(BloecoDataSource source, MySqlTransactionManager transactions,
                              Clock clock, AutoCloseable ownedDataSource) {
        this.bankRepository = new BankRepository(Objects.requireNonNull(clock, "clock"));
        this.dataSource = Objects.requireNonNull(source, "source").dataSource();
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.ownedDataSource = ownedDataSource;
        MySqlMigrations.migrate(dataSource, transactions);
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
        return transactions.execute(connection -> {
            try (SqliteBankingStore session = bankRepository.session(connection)) {
                return work.apply(session);
            }
        });
    }

    @Override public void close() {
        if (ownedDataSource == null) return;
        try { ownedDataSource.close(); }
        catch (Exception exception) { throw storage(exception); }
    }

    private static LedgerException storage(Exception cause) {
        return new LedgerException(LedgerException.Code.STORAGE_UNAVAILABLE, "MySQL 银行账本暂时不可用", cause);
    }

    @FunctionalInterface private interface Work<T> { T apply(SqliteBankingStore store); }
}
