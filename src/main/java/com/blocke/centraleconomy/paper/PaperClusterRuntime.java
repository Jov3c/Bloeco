package com.blocke.centraleconomy.paper;

import com.blocke.centraleconomy.cluster.AsyncClusterDirectory;
import com.blocke.centraleconomy.cluster.ClusterEvent;
import com.blocke.centraleconomy.cluster.ClusterEventCodec;
import com.blocke.centraleconomy.cluster.ClusterSettings;
import com.blocke.centraleconomy.cluster.PlayerIdentity;
import com.blocke.centraleconomy.cluster.PlayerNotification;
import com.blocke.centraleconomy.domain.money.Money;
import com.blocke.centraleconomy.storage.redis.RedisClusterCoordinator;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class PaperClusterRuntime implements Listener, AutoCloseable {
    private final Plugin plugin;
    private final ClusterSettings settings;
    private final AsyncClusterDirectory directory;
    private final RedisClusterCoordinator coordinator;
    private final BukkitTask task;
    private final Map<UUID, PlayerIdentity> localPlayers = new ConcurrentHashMap<>();

    public PaperClusterRuntime(Plugin plugin, ClusterSettings settings,
                               AsyncClusterDirectory directory, RedisClusterCoordinator coordinator) {
        this.plugin = plugin;
        this.settings = settings;
        this.directory = directory;
        this.coordinator = coordinator;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        directory.registerNode(settings.clusterId(), settings.serverId(), Instant.now());
        for (Player player : Bukkit.getOnlinePlayers()) online(player);
        long ticks = Math.max(20L, settings.heartbeat().toSeconds() * 20L);
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::tick, 1L, ticks);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { online(event.getPlayer()); }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        localPlayers.remove(event.getPlayer().getUniqueId());
        directory.playerOffline(event.getPlayer().getUniqueId(), settings.serverId(), Instant.now());
    }

    private void online(Player player) {
        Instant now = Instant.now();
        localPlayers.put(player.getUniqueId(), new PlayerIdentity(
                player.getUniqueId(), player.getName(), settings.serverId(), true, now));
        directory.playerOnline(player.getUniqueId(), player.getName(), settings.serverId(), now)
                .thenRun(() -> deliverPending(player.getUniqueId()));
    }

    private void tick() {
        if (!plugin.isEnabled()) return;
        List<PlayerIdentity> online = List.copyOf(localPlayers.values());
        coordinator.heartbeat(online);
        directory.registerNode(settings.clusterId(), settings.serverId(), Instant.now());
        for (PlayerIdentity player : online) deliverPending(player.playerId());
        for (ClusterEvent event : coordinator.readPending(50)) handle(event);
        for (ClusterEvent event : coordinator.readNew(100, Duration.ZERO)) handle(event);
    }

    private void handle(ClusterEvent event) {
        if (!"PLAYER_NOTIFICATION".equals(event.eventType())) {
            coordinator.acknowledge(event);
            return;
        }
        ClusterEventCodec.decodePlayerNotification(event.payload()).ifPresentOrElse(notification -> {
            if (!localPlayers.containsKey(notification.playerId())) {
                coordinator.acknowledge(event);
                return;
            }
            claimAndSend(notification, notification.playerId());
            coordinator.acknowledge(event);
        }, () -> coordinator.acknowledge(event));
    }

    private void deliverPending(java.util.UUID playerId) {
        directory.pending(playerId, 100).thenAccept(result -> {
            if (!result.isSuccess()) return;
            for (PlayerNotification notification : result.value()) claimAndSend(notification, playerId);
        });
    }

    private void claimAndSend(PlayerNotification notification, java.util.UUID playerId) {
        directory.claimNotification(notification.notificationId(), settings.serverId(), Instant.now())
                .thenAccept(result -> {
                    if (!result.isSuccess() || !result.value()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Player current = Bukkit.getPlayer(playerId);
                        if (current != null && current.isOnline()) current.sendMessage(
                                "收到转账 " + MessageFormatter.money(Money.ofMinor(notification.amountMinor())) + "。");
                    });
                });
    }

    @Override public void close() {
        task.cancel();
        Instant now = Instant.now();
        var updates = localPlayers.keySet().stream()
                .map(playerId -> directory.playerOffline(playerId, settings.serverId(), now).toCompletableFuture())
                .toList();
        localPlayers.clear();
        var stopped = directory.stopNode(settings.clusterId(), settings.serverId(), now).toCompletableFuture();
        try {
            CompletableFuture.allOf(
                    java.util.stream.Stream.concat(updates.stream(), java.util.stream.Stream.of(stopped))
                            .toArray(CompletableFuture[]::new)).get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Redis leases still expire, and stale database presence is never a balance authority.
        }
    }
}
