package com.blocke.centraleconomy.storage.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Optional cache/event accelerator. MySQL remains authoritative in every Redis state. */
public final class RedisEconomyBridge implements AutoCloseable {
    public enum HealthState { AVAILABLE, DEGRADED, RECONNECTING }

    private final RedisConnector connector;
    private final String prefix;
    private final String stream;
    private final Duration cacheTtl;
    private final ScheduledExecutorService reconnectExecutor;
    private final AtomicReference<HealthState> state = new AtomicReference<>(HealthState.DEGRADED);
    private final AtomicLong failureCount = new AtomicLong();
    private final AtomicLong lastFailureEpochMs = new AtomicLong();
    private final AtomicLong lastRecoveryEpochMs = new AtomicLong();
    private final AtomicLong reconnectAttempts = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object reconnectLock = new Object();
    private volatile RedisTransport transport;

    RedisEconomyBridge(RedisConnector connector, String prefix, String stream,
                       Duration cacheTtl, Duration reconnectInterval) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.prefix = prefix == null ? "bloeco:v2:" : prefix;
        this.stream = stream == null ? "bloeco:v2:ledger-events" : stream;
        this.cacheTtl = Objects.requireNonNull(cacheTtl, "cacheTtl");
        this.reconnectExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Bloeco-Redis-Reconnect");
            thread.setDaemon(true);
            return thread;
        });
        probeNow();
        long delay = Math.max(5_000L, Math.min(30_000L, reconnectInterval.toMillis()));
        reconnectExecutor.scheduleWithFixedDelay(this::probeNow, delay, delay, TimeUnit.MILLISECONDS);
    }

    public static RedisEconomyBridge connect(String uri, String keyPrefix, String stream, long ttlSeconds) {
        return connect(uri, keyPrefix, stream, ttlSeconds, 10L);
    }

    public static RedisEconomyBridge connect(String uri, String keyPrefix, String stream,
                                              long ttlSeconds, long reconnectSeconds) {
        RedisClient client = RedisClient.create(RedisURI.create(Objects.requireNonNull(uri, "uri")));
        return new RedisEconomyBridge(new LettuceConnector(client), keyPrefix, stream,
                Duration.ofSeconds(Math.max(1, ttlSeconds)),
                Duration.ofSeconds(Math.max(5, Math.min(30, reconnectSeconds))));
    }

    public boolean isAvailable() { return state.get() == HealthState.AVAILABLE; }
    public HealthState healthState() { return state.get(); }
    public long failureCount() { return failureCount.get(); }
    public long lastFailureEpochMs() { return lastFailureEpochMs.get(); }
    public long lastRecoveryEpochMs() { return lastRecoveryEpochMs.get(); }
    public long reconnectAttempts() { return reconnectAttempts.get(); }

    public void cache(String key, String value) {
        RedisTransport current = availableTransport();
        if (current == null) return;
        try { current.setex(prefix + key, cacheTtl.toSeconds(), value); }
        catch (RuntimeException failure) { degrade(current); }
    }

    public String cached(String key) {
        RedisTransport current = availableTransport();
        if (current == null) return null;
        try { return current.get(prefix + key); }
        catch (RuntimeException failure) { degrade(current); return null; }
    }

    /** Returns true only when Redis acknowledged the stream append. Callers may then mark Outbox published. */
    public boolean publish(String eventId, String eventType, String journalId,
                           String payload, long createdAtEpochMs) {
        RedisTransport current = availableTransport();
        if (current == null) return false;
        try {
            current.xadd(stream, Map.of(
                    "event_id", eventId,
                    "event_type", eventType,
                    "journal_id", journalId == null ? "" : journalId,
                    "payload", payload == null ? "{}" : payload,
                    "created_at", Long.toString(createdAtEpochMs)));
            return true;
        } catch (RuntimeException failure) {
            degrade(current);
            return false;
        }
    }

    void probeNow() {
        synchronized (reconnectLock) {
            if (closed.get()) return;
            RedisTransport current = transport;
            if (state.get() == HealthState.AVAILABLE && current != null) {
                try { current.ping(); return; }
                catch (RuntimeException failure) { degradeLocked(current); }
            }
            if (!state.compareAndSet(HealthState.DEGRADED, HealthState.RECONNECTING)) return;
            reconnectAttempts.incrementAndGet();
            try {
                RedisTransport replacement = connector.connect();
                replacement.ping();
                RedisTransport previous = transport;
                transport = replacement;
                if (previous != null && previous != replacement) closeQuietly(previous);
                lastRecoveryEpochMs.set(System.currentTimeMillis());
                state.set(HealthState.AVAILABLE);
            } catch (RuntimeException failure) {
                recordFailure();
                state.set(HealthState.DEGRADED);
            }
        }
    }

    private RedisTransport availableTransport() {
        return state.get() == HealthState.AVAILABLE ? transport : null;
    }

    private void degrade(RedisTransport failed) {
        synchronized (reconnectLock) {
            degradeLocked(failed);
        }
    }

    private void degradeLocked(RedisTransport failed) {
        if (failed != transport && state.get() == HealthState.AVAILABLE) {
            closeQuietly(failed);
            return;
        }
        if (transport == failed) transport = null;
        recordFailure();
        state.set(HealthState.DEGRADED);
        closeQuietly(failed);
    }

    private void recordFailure() {
        failureCount.incrementAndGet();
        lastFailureEpochMs.set(System.currentTimeMillis());
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try { closeable.close(); } catch (Exception ignored) { }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        reconnectExecutor.shutdownNow();
        synchronized (reconnectLock) {
            RedisTransport current = transport;
            transport = null;
            if (current != null) closeQuietly(current);
            closeQuietly(connector);
        }
    }

    interface RedisConnector extends AutoCloseable {
        RedisTransport connect();
        @Override default void close() { }
    }

    interface RedisTransport extends AutoCloseable {
        void ping();
        void setex(String key, long seconds, String value);
        String get(String key);
        void xadd(String stream, Map<String, String> values);
        @Override void close();
    }

    private static final class LettuceConnector implements RedisConnector {
        private final RedisClient client;
        private LettuceConnector(RedisClient client) { this.client = client; }
        @Override public RedisTransport connect() {
            StatefulRedisConnection<String, String> connection = client.connect();
            return new RedisTransport() {
                @Override public void ping() { connection.sync().ping(); }
                @Override public void setex(String key, long seconds, String value) {
                    connection.sync().setex(key, seconds, value);
                }
                @Override public String get(String key) { return connection.sync().get(key); }
                @Override public void xadd(String stream, Map<String, String> values) {
                    connection.sync().xadd(stream, values);
                }
                @Override public void close() { connection.close(); }
            };
        }
        @Override public void close() { client.shutdown(); }
    }
}
