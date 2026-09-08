package com.blocke.centraleconomy.application;

import com.blocke.centraleconomy.application.result.ErrorCode;
import com.blocke.centraleconomy.application.result.Result;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.storage.sqlite.SqliteLedgerStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
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
    void startupFailureReturnsSafeErrorWithoutLeakingCause() {
        facade = new AsyncEconomyFacade(() -> { throw new IllegalStateException("secret-path"); }, Clock.systemUTC());

        Result<Void> result = facade.readyStage().toCompletableFuture().join();

        assertEquals(ErrorCode.STORAGE_UNAVAILABLE, result.errorCode());
        assertEquals("经济账本暂时不可用。", result.message());
    }
}
