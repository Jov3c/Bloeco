package com.blocke.centraleconomy.storage.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional Redis accelerator for Bloeco. It never owns balances or decides whether a journal commits.
 * A connection failure degrades this bridge to a no-op until the next plugin restart.
 */
public final class RedisEconomyBridge implements AutoCloseable {
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final String prefix;
    private final String stream;
    private final Duration cacheTtl;
    private final AtomicBoolean available = new AtomicBoolean(true);

    private RedisEconomyBridge(RedisClient client,
                               StatefulRedisConnection<String, String> connection,
                               String prefix, String stream, Duration cacheTtl) {
        this.client = client;
        this.connection = connection;
        this.commands = connection.sync();
        this.prefix = prefix;
        this.stream = stream;
        this.cacheTtl = cacheTtl;
    }

    public static RedisEconomyBridge connect(String uri, String keyPrefix, String stream, long ttlSeconds) {
        Objects.requireNonNull(uri, "uri");
        RedisClient client = RedisClient.create(RedisURI.create(uri));
        try {
            StatefulRedisConnection<String, String> connection = client.connect();
            connection.sync().ping();
            return new RedisEconomyBridge(client, connection,
                    keyPrefix == null ? "bloeco:v2:" : keyPrefix,
                    stream == null ? "bloeco:v2:ledger-events" : stream,
                    Duration.ofSeconds(Math.max(1, ttlSeconds)));
        } catch (RuntimeException exception) {
            client.shutdown();
            throw exception;
        }
    }

    public boolean isAvailable() {
        return available.get();
    }

    public void cache(String key, String value) {
        if (!available.get()) return;
        try {
            commands.setex(prefix + key, cacheTtl.toSeconds(), value);
        } catch (RuntimeException exception) {
            available.set(false);
        }
    }

    public String cached(String key) {
        if (!available.get()) return null;
        try {
            return commands.get(prefix + key);
        } catch (RuntimeException exception) {
            available.set(false);
            return null;
        }
    }

    /** Publishes a notification only; the corresponding MySQL outbox row remains the authority. */
    public void publish(String eventId, String eventType, String journalId, String payload, long createdAtEpochMs) {
        if (!available.get()) return;
        try {
            commands.xadd(stream, Map.of(
                    "event_id", eventId,
                    "event_type", eventType,
                    "journal_id", journalId == null ? "" : journalId,
                    "payload", payload == null ? "{}" : payload,
                    "created_at", Long.toString(createdAtEpochMs)));
        } catch (RuntimeException exception) {
            available.set(false);
        }
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
