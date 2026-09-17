package com.blocke.centraleconomy.storage.mysql;

import com.blocke.centraleconomy.storage.redis.RedisEconomyBridge;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

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

/** Replays committed MySQL outbox rows into Redis Streams; it never writes balances. */
public final class MySqlOutboxPublisher implements AutoCloseable {
    private final HikariDataSource dataSource;
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
        this.redis = redis;
        this.warningLogger = warningLogger == null ? ignored -> { } : warningLogger;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password == null ? "" : password);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setPoolName("Bloeco-Outbox");
        config.setConnectionTimeout(5_000);
        config.setValidationTimeout(2_000);
        config.setMaxLifetime(1_800_000);
        config.setKeepaliveTime(120_000);
        this.dataSource = new HikariDataSource(config);
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
                    redis.publish(eventId.toString(), rows.getString("event_type"), aggregateId,
                            rows.getString("payload"), rows.getTimestamp("created_at").getTime());
                    if (!redis.isAvailable()) return;
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
        dataSource.close();
    }
}
