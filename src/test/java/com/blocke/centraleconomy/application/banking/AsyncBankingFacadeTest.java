package com.blocke.centraleconomy.application.banking;

import com.blocke.centraleconomy.application.AsyncEconomyFacade;
import com.blocke.centraleconomy.application.result.ErrorCode;
import com.blocke.centraleconomy.domain.banking.BankingPolicy;
import com.blocke.centraleconomy.domain.money.Money;
import com.blocke.centraleconomy.storage.sqlite.SqliteBankingStore;
import com.blocke.centraleconomy.domain.banking.BankingReceipt;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.UUID;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
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
        Clock clock = Clock.systemUTC();
        var enteredDeposit = new CompletableFuture<Thread>();
        var releaseDeposit = new CompletableFuture<Void>();
        try (var economy = AsyncEconomyFacade.sqlite(database, clock, Money.parse("1000000"));
             var bank = new AsyncBankingFacade(() -> new SqliteBankingStore(database, clock) {
                 @Override
                 public BankingReceipt deposit(UUID id, Money amount, String key) {
                     enteredDeposit.complete(Thread.currentThread());
                     releaseDeposit.orTimeout(10, TimeUnit.SECONDS).join();
                     return super.deposit(id, amount, key);
                 }
             }, economy.readyStage(), Money.parse("250000"),
                     new BankingPolicy(100, 320, 2000, Money.parse("10000"), true, 7),
                     economy::isReadOnly, economy)) {
            assertTrue(bank.readyStage().toCompletableFuture().join().isSuccess());
            assertTrue(economy.adjustPlayerBalance(player, Money.parse("100"), "test", "银行测试", "fund")
                    .toCompletableFuture().join().isSuccess());
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate("UPDATE account_balances SET balance_minor = balance_minor + 1 "
                        + "WHERE account_id = 'fiscal:treasury'"));
            }
            var deposit = bank.deposit(player, Money.parse("10"), "initial-deposit");
            Thread worker = enteredDeposit.get(5, TimeUnit.SECONDS);
            var verification = economy.verifyIntegrity().toCompletableFuture();
            var callback = deposit.thenAccept(receipt -> {
                assertTrue(receipt.isSuccess());
                assertFalse(Thread.holdsLock(economy));
                assertFalse(verification.orTimeout(5, TimeUnit.SECONDS).join().value().valid());
            });
            try {
                var threads = ManagementFactory.getThreadMXBean();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (java.util.Arrays.stream(threads.dumpAllThreads(false, false)).noneMatch(thread ->
                        thread.getThreadState() == Thread.State.BLOCKED
                                && thread.getLockOwnerId() == worker.threadId()
                                && thread.getLockInfo().getIdentityHashCode() == System.identityHashCode(economy))) {
                    assertFalse(verification.isDone());
                    assertTrue(System.nanoTime() < deadline, "verification must reach the bank transaction guard");
                    Thread.yield();
                }
                assertFalse(economy.isReadOnly());
            } finally {
                releaseDeposit.complete(null);
            }
            callback.toCompletableFuture().get(10, TimeUnit.SECONDS);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate("UPDATE bank_deposits SET last_interest_epoch_ms = 0"));
            }
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
