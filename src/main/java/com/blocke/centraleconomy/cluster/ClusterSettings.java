package com.blocke.centraleconomy.cluster;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

public record ClusterSettings(boolean enabled, String clusterId, String serverId,
                              Duration heartbeat, Duration lease, String consumerGroupPrefix) {
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    public ClusterSettings {
        Objects.requireNonNull(clusterId, "clusterId");
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(heartbeat, "heartbeat");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(consumerGroupPrefix, "consumerGroupPrefix");
        if (!SAFE_ID.matcher(clusterId).matches() || !SAFE_ID.matcher(serverId).matches()) {
            throw new IllegalArgumentException("cluster-id and server-id must be lowercase safe identifiers");
        }
        if (heartbeat.isZero() || heartbeat.isNegative()
                || lease.compareTo(heartbeat.multipliedBy(2)) <= 0) {
            throw new IllegalArgumentException("lease must be greater than twice the heartbeat interval");
        }
        if (consumerGroupPrefix.isBlank()) throw new IllegalArgumentException("consumer group prefix is blank");
    }

    public String consumerGroup() { return consumerGroupPrefix + clusterId + ":" + serverId; }
    public String consumerName() { return serverId; }
}
