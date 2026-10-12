package com.blocke.centraleconomy.cluster;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncClusterDirectoryTest {
    @Test
    void directoryResolvesExactNamesAndClaimsEachNotificationOnce() {
        UUID playerId = UUID.fromString("30000000-0000-0000-0000-000000000003");
        UUID notificationId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        FakeStore store = new FakeStore(playerId, notificationId);

        try (AsyncClusterDirectory directory = new AsyncClusterDirectory(() -> store, 16)) {
            assertTrue(directory.readyStage().toCompletableFuture().join().isSuccess());
            assertEquals(playerId, directory.findByName("RemotePlayer")
                    .toCompletableFuture().join().value().orElseThrow().playerId());
            assertEquals(1, directory.pending(playerId, 10)
                    .toCompletableFuture().join().value().size());
            assertTrue(directory.claimNotification(notificationId, "survival-02", Instant.EPOCH)
                    .toCompletableFuture().join().value());
            assertFalse(directory.claimNotification(notificationId, "survival-02", Instant.EPOCH)
                    .toCompletableFuture().join().value());
        }
    }

    private static final class FakeStore implements ClusterDirectoryStore {
        private final PlayerIdentity player;
        private final PlayerNotification notification;
        private boolean claimed;

        private FakeStore(UUID playerId, UUID notificationId) {
            player = new PlayerIdentity(playerId, "RemotePlayer", "survival-02", true, Instant.EPOCH);
            notification = new PlayerNotification(notificationId,
                    UUID.fromString("20000000-0000-0000-0000-000000000002"),
                    playerId, 1_000L, Instant.EPOCH);
        }

        @Override public void registerNode(String clusterId, String serverId, Instant at) { }
        @Override public void stopNode(String clusterId, String serverId, Instant at) { }
        @Override public void playerOnline(UUID playerId, String name, String serverId, Instant at) { }
        @Override public void playerOffline(UUID playerId, String serverId, Instant at) { }
        @Override public Optional<PlayerIdentity> findByName(String exactName) {
            return exactName.equalsIgnoreCase(player.lastKnownName()) ? Optional.of(player) : Optional.empty();
        }
        @Override public List<PlayerNotification> pending(UUID playerId, int limit) {
            return claimed ? List.of() : new ArrayList<>(List.of(notification));
        }
        @Override public boolean claimNotification(UUID notificationId, String serverId, Instant at) {
            if (claimed || !notification.notificationId().equals(notificationId)) return false;
            claimed = true;
            return true;
        }
        @Override public void close() { }
    }
}
