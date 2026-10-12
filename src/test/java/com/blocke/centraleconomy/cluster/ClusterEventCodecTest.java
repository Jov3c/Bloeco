package com.blocke.centraleconomy.cluster;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterEventCodecTest {
    @Test
    void playerNotificationRoundTripsWithoutFloatingPointOrPlayerNames() {
        PlayerNotification notification = new PlayerNotification(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                UUID.fromString("30000000-0000-0000-0000-000000000003"),
                12_345L,
                Instant.ofEpochMilli(1_700_000_000_123L));

        String payload = ClusterEventCodec.encode(notification);
        PlayerNotification decoded = ClusterEventCodec.decodePlayerNotification(payload).orElseThrow();

        assertEquals(notification, decoded);
        assertTrue(payload.contains("\"amount_minor\":12345"));
    }

    @Test
    void malformedOrNegativeNotificationsAreRejected() {
        assertTrue(ClusterEventCodec.decodePlayerNotification("{}").isEmpty());
        assertTrue(ClusterEventCodec.decodePlayerNotification("""
                {"notification_id":"10000000-0000-0000-0000-000000000001",
                 "entry_id":"20000000-0000-0000-0000-000000000002",
                 "player_id":"30000000-0000-0000-0000-000000000003",
                 "amount_minor":-1,"created_at_epoch_ms":1}
                """).isEmpty());
    }
}
