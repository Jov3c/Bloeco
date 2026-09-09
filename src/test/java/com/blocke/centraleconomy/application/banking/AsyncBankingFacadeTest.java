package com.blocke.centraleconomy.application.banking;

import com.blocke.centraleconomy.application.AsyncEconomyFacade;
import com.blocke.centraleconomy.domain.banking.BankingPolicy;
import com.blocke.centraleconomy.domain.money.Money;
import com.blocke.centraleconomy.storage.sqlite.SqliteBankingStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncBankingFacadeTest {
    @TempDir Path temporaryDirectory;

    @Test
    void exposesBankingWithoutBlockingTheCaller() {
        Path database = temporaryDirectory.resolve("economy.db");
        Clock clock = Clock.systemUTC();
        UUID player = UUID.randomUUID();
        try (AsyncEconomyFacade economy = AsyncEconomyFacade.sqlite(database, clock, Money.parse("1000000.00"))) {
            economy.readyStage().toCompletableFuture().join();
            economy.adjustPlayerBalance(player, Money.parse("100.00"), "test", "银行测试", "fund")
                    .toCompletableFuture().join();
            BankingPolicy policy = new BankingPolicy(100, 320, 2000, Money.parse("10000"), true, 7);
            try (AsyncBankingFacade banking = new AsyncBankingFacade(
                    () -> new SqliteBankingStore(database, clock), economy.readyStage(),
                    Money.parse("250000"), policy)) {
                assertTrue(banking.readyStage().toCompletableFuture().join().isSuccess());
                assertTrue(banking.deposit(player, Money.parse("10"), "async-deposit")
                        .toCompletableFuture().join().isSuccess());
                assertEquals(Money.parse("10"), banking.playerSnapshot(player)
                        .toCompletableFuture().join().value().deposit());
            }
        }
    }
}
