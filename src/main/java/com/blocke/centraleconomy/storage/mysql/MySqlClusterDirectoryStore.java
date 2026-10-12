package com.blocke.centraleconomy.storage.mysql;

import com.blocke.centraleconomy.cluster.ClusterDirectoryStore;
import com.blocke.centraleconomy.cluster.PlayerIdentity;
import com.blocke.centraleconomy.cluster.PlayerNotification;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class MySqlClusterDirectoryStore implements ClusterDirectoryStore {
    private final DataSource dataSource;

    public MySqlClusterDirectoryStore(BloecoDataSource dataSource) {
        this.dataSource = dataSource.dataSource();
    }

    @Override public void registerNode(String clusterId, String serverId, Instant at) {
        update("""
                INSERT INTO cluster_nodes(cluster_id,server_id,status,started_at_epoch_ms,last_seen_epoch_ms,stopped_at_epoch_ms)
                VALUES (?,?,'ONLINE',?,?,NULL) AS incoming
                ON DUPLICATE KEY UPDATE status='ONLINE',started_at_epoch_ms=incoming.started_at_epoch_ms,
                  last_seen_epoch_ms=incoming.last_seen_epoch_ms,stopped_at_epoch_ms=NULL
                """, clusterId, serverId, at.toEpochMilli(), at.toEpochMilli());
    }

    @Override public void stopNode(String clusterId, String serverId, Instant at) {
        update("UPDATE cluster_nodes SET status='OFFLINE',last_seen_epoch_ms=?,stopped_at_epoch_ms=? WHERE cluster_id=? AND server_id=?",
                at.toEpochMilli(), at.toEpochMilli(), clusterId, serverId);
    }

    @Override public void playerOnline(UUID playerId, String name, String serverId, Instant at) {
        update("""
                INSERT INTO player_directory(player_uuid,last_known_name,normalized_name,server_id,online,last_seen_epoch_ms)
                VALUES (?,?,?,?,TRUE,?) AS incoming
                ON DUPLICATE KEY UPDATE last_known_name=incoming.last_known_name,normalized_name=incoming.normalized_name,
                  server_id=incoming.server_id,online=TRUE,last_seen_epoch_ms=incoming.last_seen_epoch_ms
                """, playerId, name, name.toLowerCase(Locale.ROOT), serverId, at.toEpochMilli());
    }

    @Override public void playerOffline(UUID playerId, String serverId, Instant at) {
        update("UPDATE player_directory SET online=FALSE,last_seen_epoch_ms=? WHERE player_uuid=? AND server_id=?",
                at.toEpochMilli(), playerId, serverId);
    }

    @Override public Optional<PlayerIdentity> findByName(String exactName) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT player_uuid,last_known_name,server_id,online,last_seen_epoch_ms FROM player_directory
                WHERE normalized_name=? ORDER BY last_seen_epoch_ms DESC LIMIT 1
                """)) {
            statement.setString(1, exactName.toLowerCase(Locale.ROOT));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(new PlayerIdentity(UUID.fromString(rows.getString(1)), rows.getString(2),
                        rows.getString(3), rows.getBoolean(4), Instant.ofEpochMilli(rows.getLong(5))));
            }
        } catch (Exception failure) { throw new IllegalStateException("无法查询玩家目录", failure); }
    }

    @Override public List<PlayerNotification> pending(UUID playerId, int limit) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT notification_id,entry_id,player_uuid,amount_minor,created_at_epoch_ms
                FROM player_notifications WHERE player_uuid=? AND delivered_at_epoch_ms IS NULL
                ORDER BY created_at_epoch_ms LIMIT ?
                """)) {
            statement.setString(1, playerId.toString());
            statement.setInt(2, Math.max(1, Math.min(limit, 100)));
            List<PlayerNotification> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new PlayerNotification(UUID.fromString(rows.getString(1)),
                        UUID.fromString(rows.getString(2)), UUID.fromString(rows.getString(3)), rows.getLong(4),
                        Instant.ofEpochMilli(rows.getLong(5))));
            }
            return List.copyOf(result);
        } catch (Exception failure) { throw new IllegalStateException("无法查询待送达通知", failure); }
    }

    @Override public boolean claimNotification(UUID notificationId, String serverId, Instant at) {
        return update("""
                UPDATE player_notifications SET delivered_at_epoch_ms=?,delivered_server_id=?
                WHERE notification_id=? AND delivered_at_epoch_ms IS NULL
                """, at.toEpochMilli(), serverId, notificationId) == 1;
    }

    private int update(String sql, Object... values) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                Object value = values[index];
                if (value instanceof UUID uuid) statement.setString(index + 1, uuid.toString());
                else statement.setObject(index + 1, value);
            }
            return statement.executeUpdate();
        } catch (Exception failure) { throw new IllegalStateException("无法更新跨服目录", failure); }
    }

    @Override public void close() { }
}
