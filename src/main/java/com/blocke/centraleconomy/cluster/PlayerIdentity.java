package com.blocke.centraleconomy.cluster;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PlayerIdentity(UUID playerId, String lastKnownName, String serverId,
                             boolean online, Instant lastSeen) {
    public PlayerIdentity {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(lastKnownName, "lastKnownName");
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(lastSeen, "lastSeen");
    }
}
