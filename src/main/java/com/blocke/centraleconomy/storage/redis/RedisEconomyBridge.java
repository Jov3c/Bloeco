package com.blocke.centraleconomy.storage.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.Consumer;
import io.lettuce.core.SetArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Optional cache/event accelerator. MySQL remains authoritative in every Redis state. */
public final class RedisEconomyBridge implements AutoCloseable {
    public record StreamRecord(String id, Map<String, String> fields) { }
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
    private final Set<String> consumerGroups = ConcurrentHashMap.newKeySet();

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

    public void putEphemeral(String key, String value, long ttlSeconds) {
        RedisTransport current = availableTransport();
        if (current == null) return;
        try { current.setex(prefix + key, Math.max(1L, ttlSeconds), value); }
        catch (RuntimeException failure) { degrade(current); }
    }

    public String ephemeral(String key) {
        RedisTransport current = availableTransport();
        if (current == null) return null;
        try { return current.get(prefix + key); }
        catch (RuntimeException failure) { degrade(current); return null; }
    }

    public boolean claim(String key, String value, long ttlSeconds) {
        RedisTransport current = availableTransport();
        if (current == null) return false;
        try { return current.setnxex(prefix + key, value, Math.max(1L, ttlSeconds)); }
        catch (RuntimeException failure) { degrade(current); return false; }
    }

    public boolean ensureConsumerGroup(String group) {
        if (consumerGroups.contains(group)) return true;
        RedisTransport current = availableTransport();
        if (current == null) return false;
        try {
            current.xgroupCreate(stream, group);
            consumerGroups.add(group);
            return true;
        }
        catch (RuntimeException failure) {
            if (failure.getMessage() != null && failure.getMessage().contains("BUSYGROUP")) {
                consumerGroups.add(group);
                return true;
            }
            degrade(current);
            return false;
        }
    }

    public List<StreamRecord> readGroup(String group, String consumer, int count,
                                        Duration block, boolean pending) {
        RedisTransport current = availableTransport();
        if (current == null) return List.of();
        try { return current.xreadgroup(stream, group, consumer, Math.max(1, count), block, pending); }
        catch (RuntimeException failure) { degrade(current); return List.of(); }
    }

    public boolean acknowledge(String group, String messageId) {
        RedisTransport current = availableTransport();
        if (current == null) return false;
        try { return current.xack(stream, group, messageId) > 0L; }
        catch (RuntimeException failure) { degrade(current); return false; }
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
        consumerGroups.clear();
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
        default void xgroupCreate(String stream, String group) { throw new UnsupportedOperationException(); }
        default List<StreamRecord> xreadgroup(String stream, String group, String consumer,
                                              int count, Duration block, boolean pending) {
            throw new UnsupportedOperationException();
        }
        default long xack(String stream, String group, String messageId) { throw new UnsupportedOperationException(); }
        default boolean setnxex(String key, String value, long seconds) { throw new UnsupportedOperationException(); }
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
                @Override public void xgroupCreate(String stream, String group) {
                    connection.sync().xgroupCreate(
                            XReadArgs.StreamOffset.latest(stream), group,
                            XGroupCreateArgs.Builder.mkstream());
                }
                @Override public List<StreamRecord> xreadgroup(
                        String stream, String group, String consumer, int count,
                        Duration block, boolean pending) {
                    XReadArgs args = XReadArgs.Builder.count(count);
                    if (!block.isZero() && !block.isNegative()) args.block(block);
                    XReadArgs.StreamOffset<String> offset = pending
                            ? XReadArgs.StreamOffset.from(stream, "0-0")
                            : XReadArgs.StreamOffset.lastConsumed(stream);
                    return connection.sync().xreadgroup(Consumer.from(group, consumer), args, offset)
                            .stream().map(message -> new StreamRecord(message.getId(), Map.copyOf(message.getBody())))
                            .toList();
                }
                @Override public long xack(String stream, String group, String messageId) {
                    return connection.sync().xack(stream, group, messageId);
                }
                @Override public boolean setnxex(String key, String value, long seconds) {
                    return "OK".equals(connection.sync().set(key, value, SetArgs.Builder.nx().ex(seconds)));
                }
                @Override public void close() { connection.close(); }
            };
        }
        @Override public void close() { client.shutdown(); }
    }
}
