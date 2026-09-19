package com.blocke.centraleconomy.storage.redis;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisEconomyBridgeTest {
    @Test
    void degradesWithoutRejectingCallerAndRecoversWithoutRestart() {
        FakeConnector connector = new FakeConnector();
        connector.connectable.set(false);
        try (RedisEconomyBridge bridge = new RedisEconomyBridge(connector, "test:", "events",
                Duration.ofSeconds(30), Duration.ofSeconds(30))) {
            assertEquals(RedisEconomyBridge.HealthState.DEGRADED, bridge.healthState());
            assertFalse(bridge.publish("event", "type", "journal", "{}", 1L));

            connector.connectable.set(true);
            bridge.probeNow();

            assertEquals(RedisEconomyBridge.HealthState.AVAILABLE, bridge.healthState());
            assertTrue(bridge.publish("event", "type", "journal", "{}", 1L));
            assertTrue(bridge.lastRecoveryEpochMs() > 0);
            assertTrue(bridge.reconnectAttempts() >= 2);
            assertEquals(1, connector.published.get());
        }
    }

    @Test
    void operationFailureMovesBridgeToDegradedUntilProbeSucceeds() {
        FakeConnector connector = new FakeConnector();
        try (RedisEconomyBridge bridge = new RedisEconomyBridge(connector, "test:", "events",
                Duration.ofSeconds(30), Duration.ofSeconds(30))) {
            connector.failOperations.set(true);
            assertFalse(bridge.publish("event", "type", "journal", "{}", 1L));
            assertEquals(RedisEconomyBridge.HealthState.DEGRADED, bridge.healthState());

            connector.failOperations.set(false);
            bridge.probeNow();
            assertTrue(bridge.isAvailable());
            assertTrue(bridge.failureCount() >= 1);
            assertTrue(bridge.lastFailureEpochMs() > 0);
        }
    }

    private static final class FakeConnector implements RedisEconomyBridge.RedisConnector {
        private final AtomicBoolean connectable = new AtomicBoolean(true);
        private final AtomicBoolean failOperations = new AtomicBoolean();
        private final AtomicInteger published = new AtomicInteger();
        @Override public RedisEconomyBridge.RedisTransport connect() {
            if (!connectable.get()) throw new IllegalStateException("offline");
            return new RedisEconomyBridge.RedisTransport() {
                @Override public void ping() { failIfNeeded(); }
                @Override public void setex(String key, long seconds, String value) { failIfNeeded(); }
                @Override public String get(String key) { failIfNeeded(); return null; }
                @Override public void xadd(String stream, Map<String, String> values) {
                    failIfNeeded();
                    published.incrementAndGet();
                }
                @Override public void close() { }
                private void failIfNeeded() {
                    if (failOperations.get()) throw new IllegalStateException("offline");
                }
            };
        }
    }
}
