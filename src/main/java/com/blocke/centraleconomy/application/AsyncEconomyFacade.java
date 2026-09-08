package com.blocke.centraleconomy.application;

import com.blocke.centraleconomy.application.command.PlayerPayment;
import com.blocke.centraleconomy.application.result.ErrorCode;
import com.blocke.centraleconomy.application.result.Result;
import com.blocke.centraleconomy.application.result.TransferReceipt;
import com.blocke.centraleconomy.domain.account.Account;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.ledger.LedgerException;
import com.blocke.centraleconomy.domain.money.Money;
import com.blocke.centraleconomy.domain.tax.TaxCategory;
import com.blocke.centraleconomy.domain.tax.TaxRule;
import com.blocke.centraleconomy.storage.sqlite.LegacySqliteMigrator;
import com.blocke.centraleconomy.storage.sqlite.SqliteLedgerStore;

import java.nio.file.Path;
import java.time.Clock;
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

/** Serial asynchronous boundary that prevents JDBC access from the Paper thread. */
public final class AsyncEconomyFacade implements AutoCloseable {
    private final ExecutorService executor;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicReference<Thread> worker = new AtomicReference<>();
    private final CompletableFuture<Result<Void>> ready = new CompletableFuture<>();
    private volatile Services services;

    public AsyncEconomyFacade(Supplier<? extends LedgerStore> storeFactory, Clock clock) {
        Objects.requireNonNull(storeFactory, "storeFactory");
        Objects.requireNonNull(clock, "clock");
        executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "Bloeco-Economy-1");
            thread.setDaemon(true);
            worker.set(thread);
            return thread;
        });
        executor.execute(() -> initialize(storeFactory, clock));
    }

    public static AsyncEconomyFacade sqlite(Path databasePath, Clock clock) {
        Path path = Objects.requireNonNull(databasePath, "databasePath");
        return new AsyncEconomyFacade(() -> {
            new LegacySqliteMigrator().migrateIfRequired(path);
            return new SqliteLedgerStore(path);
        }, clock);
    }

    public CompletionStage<Result<Void>> readyStage() {
        return ready;
    }

    public CompletionStage<Result<Long>> balance(AccountId accountId) {
        return submit(context -> context.store.balance(accountId));
    }

    public CompletionStage<Result<Long>> playerBalance(UUID playerId) {
        return submit(context -> {
            Account account = Account.player(playerId);
            context.store.createAccount(account);
            return context.store.balance(account.id());
        });
    }

    public CompletionStage<Result<TransferReceipt>> pay(PlayerPayment payment) {
        return submit(context -> context.payments.pay(payment));
    }

    public CompletionStage<Result<UUID>> requestIssuance(Money amount, String actorId, String reason) {
        return submit(context -> context.bank.requestIssuance(amount, actorId, reason));
    }

    public CompletionStage<Result<IssuanceRecord>> approveIssuance(UUID requestId, String actorId) {
        return submit(context -> context.bank.approveIssuance(requestId, actorId));
    }

    public CompletionStage<Result<JournalReceipt>> executeIssuance(
            UUID requestId, String actorId, String idempotencyKey) {
        return submit(context -> context.bank.executeIssuance(requestId, actorId, idempotencyKey));
    }

    public CompletionStage<Result<JournalReceipt>> retire(
            Money amount, String actorId, String memo, String idempotencyKey) {
        return submit(context -> context.bank.retireFromTreasury(amount, actorId, memo, idempotencyKey));
    }

    public CompletionStage<Result<JournalReceipt>> adjustPlayerBalance(
            UUID playerId, Money target, String actorId, String memo, String idempotencyKey) {
        return submit(context -> context.bank.adjustPlayerBalance(playerId, target, actorId, memo, idempotencyKey));
    }

    public CompletionStage<Result<TaxRule>> changeTaxRule(
            TaxCategory category, int basisPoints, long fixedMinor, String actorId, String memo) {
        return submit(context -> context.taxes.change(category, basisPoints, fixedMinor, actorId, memo));
    }

    public CompletionStage<Result<TaxRule>> currentTaxRule(TaxCategory category) {
        return submit(context -> context.taxes.current(category));
    }

    public CompletionStage<Result<MonetaryTotals>> monetaryTotals() {
        return submit(context -> context.store.monetaryTotals());
    }

    public CompletionStage<Result<IntegrityReport>> verifyIntegrity() {
        return submit(context -> context.store.verifyIntegrity());
    }

    private void initialize(Supplier<? extends LedgerStore> storeFactory, Clock clock) {
        try {
            LedgerStore store = storeFactory.get();
            CentralBankService bank = new CentralBankService(store, clock);
            bank.initializeCentralAccounts();
            TaxRuleService taxes = new TaxRuleService(store, clock);
            taxes.initializeDefaults();
            services = new Services(store, bank, taxes, new PlayerPaymentService(store, taxes, clock));
            ready.complete(Result.success(null));
        } catch (RuntimeException exception) {
            ready.complete(Result.failure(ErrorCode.STORAGE_UNAVAILABLE, "经济账本暂时不可用。"));
        }
    }

    private <T> CompletionStage<Result<T>> submit(Function<Services, T> operation) {
        if (!accepting.get()) {
            return CompletableFuture.completedFuture(unavailable());
        }
        CompletableFuture<Result<T>> result = new CompletableFuture<>();
        executor.execute(() -> {
            if (services == null) {
                result.complete(unavailable());
                return;
            }
            try {
                result.complete(Result.success(operation.apply(services)));
            } catch (LedgerException exception) {
                result.complete(Result.failure(map(exception.code()), exception.getMessage()));
            } catch (IllegalArgumentException exception) {
                result.complete(Result.failure(ErrorCode.INVALID_REQUEST, exception.getMessage()));
            } catch (RuntimeException exception) {
                result.complete(Result.failure(ErrorCode.INTERNAL_ERROR, "经济操作失败。"));
            }
        });
        return result;
    }

    @Override
    public void close() {
        if (!accepting.getAndSet(false)) return;
        Runnable closeStore = () -> {
            Services context = services;
            if (context != null) {
                context.store.close();
                services = null;
            }
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
        return Result.failure(ErrorCode.STORAGE_UNAVAILABLE, "经济账本暂时不可用。");
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

    private record Services(
            LedgerStore store,
            CentralBankService bank,
            TaxRuleService taxes,
            PlayerPaymentService payments) {}
}
