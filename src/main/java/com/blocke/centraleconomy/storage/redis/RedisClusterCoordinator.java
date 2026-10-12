package com.blocke.centraleconomy.storage.redis;

import com.blocke.centraleconomy.cluster.ClusterEvent;
import com.blocke.centraleconomy.cluster.ClusterSettings;
import com.blocke.centraleconomy.cluster.PlayerIdentity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class RedisClusterCoordinator {
    private final RedisEconomyBridge bridge;
    private final ClusterSettings settings;
    private final Clock clock;

    public RedisClusterCoordinator(RedisEconomyBridge bridge, ClusterSettings settings, Clock clock) {
        this.bridge = bridge;
        this.settings = settings;
        this.clock = clock;
        bridge.ensureConsumerGroup(settings.consumerGroup());
    }

    public String consumerGroup() { return settings.consumerGroup(); }

    public void heartbeat(List<PlayerIdentity> players) {
        long ttl = settings.lease().toSeconds();
        bridge.putEphemeral(key("node:" + settings.serverId()), Long.toString(clock.millis()), ttl);
        for (PlayerIdentity player : players) {
            String value = player.playerId() + "|" + player.lastKnownName() + "|"
                    + settings.serverId() + "|" + clock.millis();
            bridge.putEphemeral(key("player:" + player.playerId()), value, ttl);
            bridge.putEphemeral(key("name:" + normalize(player.lastKnownName())), player.playerId().toString(), ttl);
        }
    }

    public Optional<PlayerIdentity> findOnlinePlayer(String exactName) {
        String rawId = bridge.ephemeral(key("name:" + normalize(exactName)));
        if (rawId == null) return Optional.empty();
        try {
            UUID id = UUID.fromString(rawId);
            String value = bridge.ephemeral(key("player:" + id));
            if (value == null) return Optional.empty();
            String[] fields = value.split("\\|", -1);
            if (fields.length != 4 || bridge.ephemeral(key("node:" + fields[2])) == null) return Optional.empty();
            return Optional.of(new PlayerIdentity(id, fields[1], fields[2], true,
                    Instant.ofEpochMilli(Long.parseLong(fields[3]))));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    public List<ClusterEvent> readNew(int count, Duration block) {
        return read(count, block, false);
    }

    public List<ClusterEvent> readPending(int count) {
        return read(count, Duration.ZERO, true);
    }

    public boolean claimEvent(UUID eventId) {
        return bridge.claim(key("dedup:" + eventId), settings.serverId(), settings.lease().toSeconds() * 4L);
    }

    public void acknowledge(ClusterEvent event) {
        bridge.acknowledge(settings.consumerGroup(), event.streamId());
    }

    private List<ClusterEvent> read(int count, Duration block, boolean pending) {
        if (!bridge.ensureConsumerGroup(settings.consumerGroup())) return List.of();
        List<ClusterEvent> result = new ArrayList<>();
        for (RedisEconomyBridge.StreamRecord record : bridge.readGroup(
                settings.consumerGroup(), settings.consumerName(), count, block, pending)) {
            Map<String, String> fields = record.fields();
            try {
                result.add(new ClusterEvent(record.id(), UUID.fromString(fields.get("event_id")),
                        fields.get("event_type"), fields.getOrDefault("journal_id", ""),
                        fields.getOrDefault("payload", "{}"),
                        Instant.ofEpochMilli(Long.parseLong(fields.get("created_at")))));
            } catch (RuntimeException ignored) {
                // A poison record must not remain in the pending-entry list forever.
                bridge.acknowledge(settings.consumerGroup(), record.id());
            }
        }
        return List.copyOf(result);
    }

    private String key(String suffix) { return "cluster:" + settings.clusterId() + ":" + suffix; }
    private static String normalize(String name) { return name.toLowerCase(Locale.ROOT); }
}
