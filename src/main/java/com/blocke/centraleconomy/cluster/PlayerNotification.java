package com.blocke.centraleconomy.cluster;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PlayerNotification(UUID notificationId, UUID entryId, UUID playerId,
                                 long amountMinor, Instant createdAt) {
    public PlayerNotification {
        Objects.requireNonNull(notificationId, "notificationId");
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (amountMinor < 0L) throw new IllegalArgumentException("amountMinor must not be negative");
    }
}
