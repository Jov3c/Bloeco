package com.blocke.centraleconomy.paper;

import com.blocke.centraleconomy.cluster.AsyncClusterDirectory;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class ClusterPaymentTargetResolver implements PaymentTargetResolver {
    private final AsyncClusterDirectory directory;

    public ClusterPaymentTargetResolver(AsyncClusterDirectory directory) { this.directory = directory; }

    @Override public CompletionStage<Optional<PaymentTarget>> resolveExact(String name) {
        Player local = Bukkit.getPlayerExact(name);
        if (local != null && local.isOnline()) return CompletableFuture.completedFuture(
                Optional.of(new PaymentTarget(local.getUniqueId(), local.getName())));
        return directory.findByName(name).thenApply(result -> {
            if (!result.isSuccess()) return Optional.empty();
            return result.value().map(player -> new PaymentTarget(player.playerId(), player.lastKnownName()));
        });
    }

    @Override public List<String> suggestions(String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(normalized))
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
}
