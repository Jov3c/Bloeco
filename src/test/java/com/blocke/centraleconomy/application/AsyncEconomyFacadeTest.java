package com.blocke.centraleconomy.application;

import com.blocke.centraleconomy.application.result.ErrorCode;
import com.blocke.centraleconomy.application.result.Result;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.account.Account;
import com.blocke.centraleconomy.domain.money.Money;
import com.blocke.centraleconomy.storage.sqlite.SqliteLedgerStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncEconomyFacadeTest {
    @TempDir Path temporaryDirectory;
    private AsyncEconomyFacade facade;

    @AfterEach
    void tearDown() {
        if (facade != null) facade.close();
    }

    @Test
    void ledgerWorkNeverRunsOnTheCallingThread() {
        String caller = Thread.currentThread().getName();
        AtomicReference<String> balanceThread = new AtomicReference<>();
        SqliteLedgerStore delegate = new SqliteLedgerStore(temporaryDirectory.resolve("economy.db"));
        LedgerStore recording = (LedgerStore) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{LedgerStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("balance")) balanceThread.set(Thread.currentThread().getName());
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
        facade = new AsyncEconomyFacade(() -> recording, Clock.systemUTC());
        assertTrue(facade.readyStage().toCompletableFuture().join().isSuccess());

        Result<Long> result = facade.balance(AccountId.treasury()).toCompletableFuture().join();

        assertTrue(result.isSuccess());
        assertNotEquals(caller, balanceThread.get());
        assertTrue(balanceThread.get().startsWith("Bloeco-Economy-"));
    }

    @Test
    void shutdownDrainsAcceptedWorkAndRejectsNewWork() {
        facade = AsyncEconomyFacade.sqlite(temporaryDirectory.resolve("economy.db"), Clock.systemUTC());
        assertTrue(facade.readyStage().toCompletableFuture().join().isSuccess());
        var accepted = facade.balance(AccountId.treasury());

        facade.close();

        assertTrue(accepted.toCompletableFuture().join().isSuccess());
        Result<Long> rejected = facade.balance(AccountId.treasury()).toCompletableFuture().join();
        assertEquals(ErrorCode.STORAGE_UNAVAILABLE, rejected.errorCode());
    }

    @Test
    void readingANewPlayerBalanceCreatesAZeroBalanceWallet() {
        facade = AsyncEconomyFacade.sqlite(temporaryDirectory.resolve("economy.db"), Clock.systemUTC());
        assertTrue(facade.readyStage().toCompletableFuture().join().isSuccess());

        Result<Long> result = facade.playerBalance(UUID.randomUUID()).toCompletableFuture().join();

        assertTrue(result.isSuccess());
        assertEquals(0L, result.value());
    }

    @Test
    void startupFailureReturnsSafeErrorWithoutLeakingCause() {
        facade = new AsyncEconomyFacade(() -> { throw new IllegalStateException("secret-path"); }, Clock.systemUTC());

        Result<Void> result = facade.readyStage().toCompletableFuture().join();

        assertEquals(ErrorCode.STORAGE_UNAVAILABLE, result.errorCode());
        assertEquals("经济账本暂时不可用。", result.message());
    }

    @Test
    void startupIntegrityFailureEnablesReadOnlyProtectionButKeepsQueriesAvailable() {
        SqliteLedgerStore delegate = new SqliteLedgerStore(temporaryDirectory.resolve("economy.db"));
        new CentralBankService(delegate, Clock.systemUTC()).initializeCentralAccounts();
        UUID existingPlayer = UUID.randomUUID();
        delegate.createAccount(Account.player(existingPlayer));
        LedgerStore corrupted = (LedgerStore) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{LedgerStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("verifyIntegrity")) {
                        return new IntegrityReport(false, List.of("balance mismatch fixture"));
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
        facade = new AsyncEconomyFacade(() -> corrupted, Clock.systemUTC());

        assertTrue(facade.readyStage().toCompletableFuture().join().isSuccess());
        assertTrue(facade.isReadOnly());
        assertTrue(facade.monetaryTotals().toCompletableFuture().join().isSuccess());
        assertEquals(0L, facade.playerBalance(existingPlayer).toCompletableFuture().join().value());
        Result<UUID> write = facade.requestIssuance(
                Money.ofMinor(100), "admin:requester", "must be rejected").toCompletableFuture().join();
        assertEquals(ErrorCode.INTEGRITY_FAILURE, write.errorCode());
    }
}
