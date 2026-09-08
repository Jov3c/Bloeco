package com.blocke.centraleconomy.paper;

import com.blocke.centraleconomy.application.AsyncEconomyFacade;
import com.blocke.centraleconomy.domain.money.Money;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

/** Grants the configured one-time starter allocation without creating currency. */
public final class PlayerStarterFundsListener implements Listener {
    private final JavaPlugin plugin;
    private final AsyncEconomyFacade economy;
    private final Money amount;

    public PlayerStarterFundsListener(JavaPlugin plugin, AsyncEconomyFacade economy, Money amount) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.amount = Objects.requireNonNull(amount, "amount");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        economy.grantStarterFunds(event.getPlayer().getUniqueId(), amount).thenAccept(result -> {
            if (!result.isSuccess()) {
                plugin.getLogger().warning("Could not allocate starter funds to "
                        + event.getPlayer().getUniqueId() + ": " + result.message());
            }
        });
    }
}
