package com.blocke.centraleconomy.paper;

import com.blocke.centraleconomy.application.AsyncEconomyFacade;
import com.blocke.centraleconomy.application.banking.AsyncBankingFacade;
import com.blocke.centraleconomy.application.result.Result;
import com.blocke.centraleconomy.application.IntegrityReport;
import com.blocke.centraleconomy.domain.money.Money;
import com.blocke.centraleconomy.domain.banking.BankingPolicy;
import com.blocke.centraleconomy.storage.mysql.MySqlBankingStore;
import com.blocke.centraleconomy.storage.sqlite.SqliteBankingStore;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Paper lifecycle owner for the asynchronous economy core. */
public final class BloecoRuntime implements AutoCloseable {
    private final AsyncEconomyFacade facade;
    private final AsyncBankingFacade banking;

    private BloecoRuntime(AsyncEconomyFacade facade, AsyncBankingFacade banking) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.banking = banking;
    }

    public static BloecoRuntime sqlite(Path databasePath) {
        return sqlite(databasePath, Money.parse("1000000"), Money.parse("250000"), defaultBankingPolicy());
    }

    public static BloecoRuntime sqlite(Path databasePath, Money initialTreasury) {
        return sqlite(databasePath, initialTreasury, Money.parse("250000"), defaultBankingPolicy());
    }

    public static BloecoRuntime sqlite(Path databasePath, Money initialTreasury,
                                       Money initialBankCapital, BankingPolicy policy) {
        Clock clock = Clock.systemUTC();
        AsyncEconomyFacade economy = AsyncEconomyFacade.sqlite(databasePath, clock, initialTreasury);
        AsyncBankingFacade bank = new AsyncBankingFacade(
                () -> new SqliteBankingStore(databasePath, clock), economy.readyStage(), initialBankCapital, policy);
        return new BloecoRuntime(economy, bank);
    }

    public static BloecoRuntime mysql(String jdbcUrl, String username, String password,
                                      int maximumPoolSize, Money initialTreasury) {
        return mysql(jdbcUrl, username, password, maximumPoolSize, initialTreasury,
                Money.parse("250000"), defaultBankingPolicy());
    }

    public static BloecoRuntime mysql(String jdbcUrl, String username, String password,
                                      int maximumPoolSize, Money initialTreasury,
                                      Money initialBankCapital, BankingPolicy policy) {
        Clock clock = Clock.systemUTC();
        AsyncEconomyFacade economy = AsyncEconomyFacade.mysql(
                jdbcUrl, username, password, maximumPoolSize, clock, initialTreasury);
        AsyncBankingFacade bank = new AsyncBankingFacade(
                () -> new MySqlBankingStore(jdbcUrl, username, password, maximumPoolSize, clock),
                economy.readyStage(), initialBankCapital, policy);
        return new BloecoRuntime(economy, bank);
    }

    public AsyncEconomyFacade facade() { return facade; }
    public AsyncBankingFacade banking() { return banking; }
    public CompletionStage<Result<Void>> readyStage() { return facade.readyStage(); }
    public CompletionStage<Result<IntegrityReport>> verifyNow() { return facade.verifyIntegrity(); }
    public boolean isReadOnly() { return facade.isReadOnly(); }
    @Override public void close() {
        if (banking != null) banking.close();
        facade.close();
    }

    private static BankingPolicy defaultBankingPolicy() {
        return new BankingPolicy(100, 320, 2000, Money.parse("10000"), true, 7);
    }
}
