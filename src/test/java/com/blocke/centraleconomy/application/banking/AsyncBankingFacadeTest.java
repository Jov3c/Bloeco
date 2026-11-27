package com.blocke.centraleconomy.application.banking;

import com.blocke.centraleconomy.application.AsyncEconomyFacade;
import com.blocke.centraleconomy.application.result.ErrorCode;
import com.blocke.centraleconomy.domain.banking.BankingPolicy;
import com.blocke.centraleconomy.domain.money.Money;
import com.blocke.centraleconomy.paper.BloecoRuntime;
import com.blocke.centraleconomy.storage.sqlite.SqliteBankingStore;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.UUID;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncBankingFacadeTest {
    @TempDir Path temporaryDirectory;

    @Test
    void ledgerMismatchBlocksBankWritesButKeepsBankTotalsReadable() throws Exception {
        Path database = temporaryDirectory.resolve("economy.db");
        UUID player = UUID.randomUUID();
        try (BloecoRuntime runtime = BloecoRuntime.sqlite(database)) {
            var bank = runtime.banking();
            assertTrue(bank.readyStage().toCompletableFuture().join().isSuccess());
            assertTrue(runtime.facade().adjustPlayerBalance(player, Money.parse("100"), "test", "银行测试", "fund")
                    .toCompletableFuture().join().isSuccess());
            assertTrue(bank.deposit(player, Money.parse("10"), "initial-deposit").toCompletableFuture().join().isSuccess());
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate("UPDATE account_balances SET balance_minor = balance_minor + 1 "
                        + "WHERE account_id = 'fiscal:treasury'"));
                assertEquals(1, statement.executeUpdate("UPDATE bank_deposits SET last_interest_epoch_ms = 0"));
            }
            assertFalse(runtime.verifyNow().toCompletableFuture().join().value().valid());
            assertEquals(ErrorCode.INTEGRITY_FAILURE,
                    bank.deposit(player, Money.parse("10"), "blocked-deposit").toCompletableFuture().join().errorCode());
            assertEquals(Money.parse("10"), bank.playerSnapshot(player).toCompletableFuture().join().value().deposit());
            assertTrue(bank.bankSnapshot().toCompletableFuture().join().isSuccess());
        }
    }

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

    @Test
    void executorRejectionReturnsUnavailableResult() throws Exception {
        Path database = temporaryDirectory.resolve("economy.db");
        Clock clock = Clock.systemUTC();
        try (AsyncEconomyFacade economy = AsyncEconomyFacade.sqlite(database, clock, Money.parse("1000000.00"))) {
            assertTrue(economy.readyStage().toCompletableFuture().join().isSuccess());
            BankingPolicy policy = new BankingPolicy(100, 320, 2000, Money.parse("10000"), true, 7);
            try (AsyncBankingFacade banking = new AsyncBankingFacade(
                    () -> new SqliteBankingStore(database, clock), economy.readyStage(),
                    Money.parse("250000"), policy)) {
                assertTrue(banking.readyStage().toCompletableFuture().join().isSuccess());
                Field field = AsyncBankingFacade.class.getDeclaredField("executor");
                field.setAccessible(true);
                ((ExecutorService) field.get(banking)).shutdown();

                assertEquals(ErrorCode.STORAGE_UNAVAILABLE,
                        banking.playerSnapshot(UUID.randomUUID()).toCompletableFuture().join().errorCode());
            }
        }
    }
}
