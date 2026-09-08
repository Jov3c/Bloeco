package com.blocke.centraleconomy.application.banking;

import com.blocke.centraleconomy.application.result.ErrorCode;
import com.blocke.centraleconomy.application.result.Result;
import com.blocke.centraleconomy.domain.banking.BankSnapshot;
import com.blocke.centraleconomy.domain.banking.BankingPlayerSnapshot;
import com.blocke.centraleconomy.domain.banking.BankingPolicy;
import com.blocke.centraleconomy.domain.banking.BankingReceipt;
import com.blocke.centraleconomy.domain.banking.LoanReceipt;
import com.blocke.centraleconomy.domain.ledger.LedgerException;
import com.blocke.centraleconomy.domain.money.Money;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/** Asynchronous boundary for the state-owned bank. No JDBC work runs on Paper's main thread. */
public final class AsyncBankingFacade implements AutoCloseable {
    private final ExecutorService executor;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicReference<Thread> worker = new AtomicReference<>();
    private final CompletableFuture<Result<Void>> ready = new CompletableFuture<>();
    private volatile BankingStore store;

    public AsyncBankingFacade(
            Supplier<? extends BankingStore> storeFactory,
            CompletionStage<Result<Void>> economyReady,
            Money initialCapital,
            BankingPolicy policy) {
        Objects.requireNonNull(storeFactory, "storeFactory");
        Objects.requireNonNull(economyReady, "economyReady");
        Objects.requireNonNull(initialCapital, "initialCapital");
        Objects.requireNonNull(policy, "policy");
        executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "Bloeco-Banking-1");
            thread.setDaemon(true);
            worker.set(thread);
            return thread;
        });
        economyReady.whenComplete((result, failure) -> executor.execute(() -> {
            if (failure != null || result == null || !result.isSuccess()) {
                ready.complete(unavailable());
                return;
            }
            try {
                BankingStore opened = storeFactory.get();
                opened.initialize(initialCapital, policy);
                store = opened;
                ready.complete(Result.success(null));
            } catch (RuntimeException exception) {
                ready.complete(unavailable());
            }
        }));
    }

    public CompletionStage<Result<Void>> readyStage() { return ready; }

    public CompletionStage<Result<BankingReceipt>> deposit(UUID playerId, Money amount, String key) {
        return submit(value -> value.deposit(playerId, amount, key));
    }

    public CompletionStage<Result<BankingReceipt>> withdraw(UUID playerId, Money amount, String key) {
        return submit(value -> value.withdraw(playerId, amount, key));
    }

    public CompletionStage<Result<LoanReceipt>> borrow(UUID playerId, Money amount, String key) {
        return submit(value -> value.borrow(playerId, amount, key));
    }

    public CompletionStage<Result<BankingReceipt>> repay(
            UUID playerId, UUID loanId, Money amount, String key) {
        return submit(value -> value.repay(playerId, loanId, amount, key));
    }

    public CompletionStage<Result<BankingPlayerSnapshot>> playerSnapshot(UUID playerId) {
        return submit(value -> value.playerSnapshot(playerId));
    }

    public CompletionStage<Result<BankSnapshot>> bankSnapshot() {
        return submit(BankingStore::bankSnapshot);
    }

    public CompletionStage<Result<BankingPolicy>> updatePolicy(BankingPolicy policy, String actorId) {
        return submit(value -> value.updatePolicy(policy, actorId));
    }

    private <T> CompletionStage<Result<T>> submit(Function<BankingStore, T> operation) {
        if (!accepting.get()) return CompletableFuture.completedFuture(unavailable());
        CompletableFuture<Result<T>> result = new CompletableFuture<>();
        executor.execute(() -> {
            BankingStore current = store;
            if (current == null) {
                result.complete(unavailable());
                return;
            }
            try {
                result.complete(Result.success(operation.apply(current)));
            } catch (LedgerException exception) {
                result.complete(Result.failure(map(exception.code()), exception.getMessage()));
            } catch (IllegalArgumentException exception) {
                result.complete(Result.failure(ErrorCode.INVALID_REQUEST, exception.getMessage()));
            } catch (RuntimeException exception) {
                result.complete(Result.failure(ErrorCode.INTERNAL_ERROR, "银行操作失败。"));
            }
        });
        return result;
    }

    @Override
    public void close() {
        if (!accepting.getAndSet(false)) return;
        Runnable closeStore = () -> {
            BankingStore current = store;
            if (current != null) current.close();
            store = null;
        };
        if (Thread.currentThread() == worker.get()) {
            closeStore.run();
            executor.shutdown();
            return;
        }
        CompletableFuture<Void> closed = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                closeStore.run();
                closed.complete(null);
            } catch (RuntimeException exception) {
                closed.completeExceptionally(exception);
            }
        });
        executor.shutdown();
        try {
            closed.get(30, TimeUnit.SECONDS);
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (Exception exception) {
            executor.shutdownNow();
        }
    }

    private static <T> Result<T> unavailable() {
        return Result.failure(ErrorCode.STORAGE_UNAVAILABLE, "国有银行暂时不可用。");
    }

    private static ErrorCode map(LedgerException.Code code) {
        return switch (code) {
            case INVALID_AMOUNT -> ErrorCode.INVALID_AMOUNT;
            case INVALID_JOURNAL -> ErrorCode.INVALID_REQUEST;
            case ACCOUNT_NOT_FOUND -> ErrorCode.ACCOUNT_NOT_FOUND;
            case ACCOUNT_FROZEN -> ErrorCode.ACCOUNT_FROZEN;
            case INSUFFICIENT_FUNDS -> ErrorCode.INSUFFICIENT_FUNDS;
            case IDEMPOTENCY_CONFLICT -> ErrorCode.IDEMPOTENCY_CONFLICT;
            case POLICY_REJECTED -> ErrorCode.POLICY_REJECTED;
            case INTEGRITY_FAILURE -> ErrorCode.INTEGRITY_FAILURE;
            case STORAGE_UNAVAILABLE -> ErrorCode.STORAGE_UNAVAILABLE;
        };
    }
}
