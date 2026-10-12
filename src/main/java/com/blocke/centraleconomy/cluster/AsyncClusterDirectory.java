package com.blocke.centraleconomy.cluster;

import com.blocke.centraleconomy.application.result.ErrorCode;
import com.blocke.centraleconomy.application.result.Result;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class AsyncClusterDirectory implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final CompletableFuture<Result<Void>> ready;
    private volatile ClusterDirectoryStore store;

    public AsyncClusterDirectory(Supplier<? extends ClusterDirectoryStore> factory, int queueCapacity) {
        this(factory, CompletableFuture.completedFuture(Result.<Void>success(null)), queueCapacity);
    }

    public AsyncClusterDirectory(Supplier<? extends ClusterDirectoryStore> factory,
                                 CompletionStage<Result<Void>> dependency, int queueCapacity) {
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(Math.max(1, queueCapacity)), task -> {
                    Thread thread = new Thread(task, "Bloeco-Cluster-Directory");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        ready = dependency.thenCompose(required -> {
            if (!required.isSuccess()) return CompletableFuture.completedFuture(
                    Result.<Void>failure(required.errorCode(), required.message()));
            return CompletableFuture.supplyAsync(() -> {
                try {
                    store = factory.get();
                    return Result.<Void>success(null);
                } catch (RuntimeException failure) {
                    return Result.<Void>failure(ErrorCode.STORAGE_UNAVAILABLE, "跨服目录暂时不可用");
                }
            }, executor);
        }).toCompletableFuture();
    }

    public CompletionStage<Result<Void>> readyStage() { return ready; }
    public CompletionStage<Result<Optional<PlayerIdentity>>> findByName(String name) {
        return submit(() -> store.findByName(name));
    }
    public CompletionStage<Result<List<PlayerNotification>>> pending(UUID playerId, int limit) {
        return submit(() -> store.pending(playerId, Math.max(1, limit)));
    }
    public CompletionStage<Result<Boolean>> claimNotification(UUID id, String serverId, Instant at) {
        return submit(() -> store.claimNotification(id, serverId, at));
    }
    public CompletionStage<Result<Void>> registerNode(String clusterId, String serverId, Instant at) {
        return submit(() -> { store.registerNode(clusterId, serverId, at); return null; });
    }
    public CompletionStage<Result<Void>> stopNode(String clusterId, String serverId, Instant at) {
        return submit(() -> { store.stopNode(clusterId, serverId, at); return null; });
    }
    public CompletionStage<Result<Void>> playerOnline(UUID id, String name, String serverId, Instant at) {
        return submit(() -> { store.playerOnline(id, name, serverId, at); return null; });
    }
    public CompletionStage<Result<Void>> playerOffline(UUID id, String serverId, Instant at) {
        return submit(() -> { store.playerOffline(id, serverId, at); return null; });
    }

    private <T> CompletionStage<Result<T>> submit(Supplier<T> work) {
        return ready.thenCompose(initialized -> {
            if (!initialized.isSuccess()) return CompletableFuture.completedFuture(
                    Result.failure(initialized.errorCode(), initialized.message()));
            try {
                return CompletableFuture.supplyAsync(() -> {
                    try { return Result.success(work.get()); }
                    catch (RuntimeException failure) {
                        return Result.failure(ErrorCode.STORAGE_UNAVAILABLE, "跨服目录暂时不可用");
                    }
                }, executor);
            } catch (RuntimeException rejected) {
                return CompletableFuture.completedFuture(Result.failure(
                        ErrorCode.STORAGE_UNAVAILABLE, "跨服目录繁忙，请稍后再试"));
            }
        });
    }

    @Override public void close() {
        executor.shutdownNow();
        ClusterDirectoryStore current = store;
        if (current != null) current.close();
    }
}
