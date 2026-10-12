package com.blocke.centraleconomy.cluster;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ClusterEvent(String streamId, UUID eventId, String eventType,
                           String aggregateId, String payload, Instant createdAt) {
    public ClusterEvent {
        Objects.requireNonNull(streamId, "streamId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
