package com.blocke.centraleconomy.storage.mysql;

import com.blocke.centraleconomy.storage.redis.RedisEconomyBridge;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.sql.DataSource;

/** Replays committed MySQL outbox rows into Redis Streams; it never writes balances. */
public final class MySqlOutboxPublisher implements AutoCloseable {
    private final DataSource dataSource;
    private final AutoCloseable ownedDataSource;
    private final RedisEconomyBridge redis;
    private final ScheduledExecutorService executor;
    private final Consumer<String> warningLogger;
    private final AtomicLong lastWarningEpochMs = new AtomicLong();

    public MySqlOutboxPublisher(String jdbcUrl, String username, String password,
                                RedisEconomyBridge redis, Duration interval) {
        this(jdbcUrl, username, password, redis, interval, ignored -> { });
    }

    public MySqlOutboxPublisher(String jdbcUrl, String username, String password,
                                RedisEconomyBridge redis, Duration interval,
                                Consumer<String> warningLogger) {
        this(new BloecoDataSource(jdbcUrl, username, password, 2), redis, interval, warningLogger, true);
    }

    public MySqlOutboxPublisher(BloecoDataSource dataSource, RedisEconomyBridge redis,
                                Duration interval, Consumer<String> warningLogger) {
        this(dataSource, redis, interval, warningLogger, false);
    }

    private MySqlOutboxPublisher(BloecoDataSource source, RedisEconomyBridge redis,
                                 Duration interval, Consumer<String> warningLogger, boolean ownsSource) {
        this.redis = redis;
        this.warningLogger = warningLogger == null ? ignored -> { } : warningLogger;
        this.dataSource = source.dataSource();
        this.ownedDataSource = ownsSource ? source : null;
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Bloeco-Redis-Outbox");
            thread.setDaemon(true);
            return thread;
        });
        long delay = Math.max(250L, interval.toMillis());
        executor.scheduleWithFixedDelay(this::publishBatch, delay, delay, TimeUnit.MILLISECONDS);
    }

    private void publishBatch() {
        if (!redis.isAvailable()) return;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT event_id, event_type, aggregate_id, payload, created_at
                     FROM outbox_events WHERE published_at IS NULL
                     ORDER BY created_at, event_id LIMIT 100
                     """)) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next() && redis.isAvailable()) {
                    UUID eventId = UUID.fromString(rows.getString("event_id"));
                    String aggregateId = rows.getString("aggregate_id");
                    boolean published = redis.publish(eventId.toString(), rows.getString("event_type"), aggregateId,
                            rows.getString("payload"), rows.getTimestamp("created_at").getTime());
                    if (!published) return;
                    try (PreparedStatement mark = connection.prepareStatement("""
                            UPDATE outbox_events SET published_at = UTC_TIMESTAMP(3), attempts = attempts + 1
                            WHERE event_id = ? AND published_at IS NULL
                            """)) {
                        mark.setString(1, eventId.toString());
                        mark.executeUpdate();
                    }
                }
            }
        } catch (Exception exception) {
            // MySQL remains correct; the next interval retries the same outbox rows.
            long now = System.currentTimeMillis();
            long previous = lastWarningEpochMs.get();
            if (now - previous >= 60_000L && lastWarningEpochMs.compareAndSet(previous, now)) {
                warningLogger.accept("Redis 事件发布暂时失败，待发布账单会保留并自动重试。");
            }
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        if (ownedDataSource != null) {
            try { ownedDataSource.close(); }
            catch (Exception ignored) { }
        }
    }
}
