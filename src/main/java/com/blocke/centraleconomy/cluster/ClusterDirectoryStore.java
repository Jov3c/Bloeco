package com.blocke.centraleconomy.cluster;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClusterDirectoryStore extends AutoCloseable {
    void registerNode(String clusterId, String serverId, Instant at);
    void stopNode(String clusterId, String serverId, Instant at);
    void playerOnline(UUID playerId, String name, String serverId, Instant at);
    void playerOffline(UUID playerId, String serverId, Instant at);
    Optional<PlayerIdentity> findByName(String exactName);
    List<PlayerNotification> pending(UUID playerId, int limit);
    boolean claimNotification(UUID notificationId, String serverId, Instant at);
    @Override void close();
}
