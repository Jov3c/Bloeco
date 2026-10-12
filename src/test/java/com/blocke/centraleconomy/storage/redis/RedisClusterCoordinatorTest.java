package com.blocke.centraleconomy.storage.redis;

import com.blocke.centraleconomy.cluster.ClusterEvent;
import com.blocke.centraleconomy.cluster.ClusterSettings;
import com.blocke.centraleconomy.cluster.PlayerIdentity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisClusterCoordinatorTest {
    @Test
    void heartbeatsResolveRemotePlayersAndEveryServerUsesItsOwnConsumerGroup() {
        FakeConnector connector = new FakeConnector();
        ClusterSettings settings = new ClusterSettings(true, "production", "survival-01",
                Duration.ofSeconds(10), Duration.ofSeconds(30), "bloeco:v2:cluster:");
        try (RedisEconomyBridge bridge = new RedisEconomyBridge(connector, "test:", "events",
                Duration.ofSeconds(60), Duration.ofSeconds(30))) {
            RedisClusterCoordinator coordinator = new RedisClusterCoordinator(
                    bridge, settings, Clock.fixed(Instant.ofEpochMilli(1234L), ZoneOffset.UTC));
            PlayerIdentity player = new PlayerIdentity(
                    UUID.fromString("30000000-0000-0000-0000-000000000003"),
                    "RemotePlayer", "survival-01", true, Instant.ofEpochMilli(1234L));

            coordinator.heartbeat(List.of(player));

            assertEquals(player.playerId(), coordinator.findOnlinePlayer("remoteplayer").orElseThrow().playerId());
            assertEquals("bloeco:v2:cluster:production:survival-01", coordinator.consumerGroup());
            assertTrue(connector.transport.values.keySet().stream().anyMatch(key -> key.contains("node:survival-01")));
        }
    }

    @Test
    void pendingAndNewMessagesAreAcknowledgedOnlyAfterExplicitHandling() {
        FakeConnector connector = new FakeConnector();
        ClusterSettings settings = new ClusterSettings(true, "production", "survival-01",
                Duration.ofSeconds(10), Duration.ofSeconds(30), "bloeco:v2:cluster:");
        try (RedisEconomyBridge bridge = new RedisEconomyBridge(connector, "test:", "events",
                Duration.ofSeconds(60), Duration.ofSeconds(30))) {
            RedisClusterCoordinator coordinator = new RedisClusterCoordinator(
                    bridge, settings, Clock.systemUTC());
            connector.transport.messages.add(new RedisEconomyBridge.StreamRecord("9-0", Map.of(
                    "event_id", "10000000-0000-0000-0000-000000000001",
                    "event_type", "JOURNAL_COMMITTED",
                    "journal_id", "20000000-0000-0000-0000-000000000002",
                    "payload", "{}",
                    "created_at", "1")));

            List<ClusterEvent> events = coordinator.readNew(10, Duration.ZERO);

            assertEquals(1, events.size());
            assertEquals(0L, connector.transport.acknowledged.get());
            coordinator.acknowledge(events.getFirst());
            assertEquals(1L, connector.transport.acknowledged.get());
        }
    }

    @Test
    void malformedMessagesAreAcknowledgedAsPoisonInsteadOfBlockingTheConsumer() {
        FakeConnector connector = new FakeConnector();
        ClusterSettings settings = new ClusterSettings(true, "production", "survival-01",
                Duration.ofSeconds(10), Duration.ofSeconds(30), "bloeco:v2:cluster:");
        try (RedisEconomyBridge bridge = new RedisEconomyBridge(connector, "test:", "events",
                Duration.ofSeconds(60), Duration.ofSeconds(30))) {
            RedisClusterCoordinator coordinator = new RedisClusterCoordinator(bridge, settings, Clock.systemUTC());
            connector.transport.messages.add(new RedisEconomyBridge.StreamRecord("10-0", Map.of(
                    "event_id", "not-a-uuid", "event_type", "BROKEN", "created_at", "1")));

            assertTrue(coordinator.readNew(10, Duration.ZERO).isEmpty());
            assertEquals(1L, connector.transport.acknowledged.get());
        }
    }

    private static final class FakeConnector implements RedisEconomyBridge.RedisConnector {
        private final FakeTransport transport = new FakeTransport();
        @Override public RedisEconomyBridge.RedisTransport connect() { return transport; }
    }

    private static final class FakeTransport implements RedisEconomyBridge.RedisTransport {
        private final Map<String, String> values = new LinkedHashMap<>();
        private final List<RedisEconomyBridge.StreamRecord> messages = new ArrayList<>();
        private final AtomicLong acknowledged = new AtomicLong();
        @Override public void ping() { }
        @Override public void setex(String key, long seconds, String value) { values.put(key, value); }
        @Override public String get(String key) { return values.get(key); }
        @Override public void xadd(String stream, Map<String, String> values) { }
        @Override public void xgroupCreate(String stream, String group) { }
        @Override public List<RedisEconomyBridge.StreamRecord> xreadgroup(
                String stream, String group, String consumer, int count, Duration block, boolean pending) {
            List<RedisEconomyBridge.StreamRecord> copy = List.copyOf(messages);
            messages.clear();
            return copy;
        }
        @Override public long xack(String stream, String group, String messageId) {
            return acknowledged.incrementAndGet();
        }
        @Override public boolean setnxex(String key, String value, long seconds) {
            return values.putIfAbsent(key, value) == null;
        }
        @Override public void close() { }
    }
}
