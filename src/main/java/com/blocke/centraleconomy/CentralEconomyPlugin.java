package com.blocke.centraleconomy;

import com.blocke.centraleconomy.paper.BloecoRuntime;
import com.blocke.centraleconomy.paper.BloecoMenu;
import com.blocke.centraleconomy.paper.EcoCommand;
import com.blocke.centraleconomy.paper.PayCommand;
import com.blocke.centraleconomy.paper.RoleAccess;
import com.blocke.centraleconomy.paper.PlayerStarterFundsListener;
import com.blocke.centraleconomy.domain.money.Money;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
public class CentralEconomyPlugin extends JavaPlugin {
    private BloecoRuntime runtime;

    @Override
    public void onEnable() {
        migrateLegacyDataFolder();
        saveDefaultConfig();
        String storageType = getConfig().getString("storage.type", "sqlite");
        if (!"sqlite".equalsIgnoreCase(storageType)) {
            throw new IllegalArgumentException("Phase 1 supports storage.type=sqlite; MySQL arrives in Phase 2");
        }
        Money initialTreasury = configuredPositiveMoney(
                "bootstrap.treasury-initial-balance", "1000000.00");
        Money initialPlayerBalance = configuredPositiveMoney(
                "bootstrap.player-initial-balance", "100.00");
        runtime = BloecoRuntime.sqlite(getDataFolder().toPath().resolve(
                getConfig().getString("storage.sqlite.file", "economy.db")), initialTreasury);
        RoleAccess roles = new RoleAccess();
        BloecoMenu menu = new BloecoMenu(this, runtime.facade(), roles);
        PayCommand pay = new PayCommand(this, runtime.facade());
        getCommand("pay").setExecutor(pay);
        getCommand("pay").setTabCompleter(pay);
        getCommand("eco").setExecutor(new EcoCommand(menu));
        getServer().getPluginManager().registerEvents(
                new PlayerStarterFundsListener(this, runtime.facade(), initialPlayerBalance), this);
        long minutes = Math.max(1L, getConfig().getLong("integrity.check-interval-minutes", 15L));
        long ticks = Math.multiplyExact(minutes, 1_200L);
        getServer().getScheduler().runTaskTimer(this, () -> runtime.verifyNow().thenAccept(result -> {
            if (!result.isSuccess() || !result.value().valid()) {
                getLogger().severe("Central ledger integrity check failed; Bloeco is now read-only.");
            }
        }), ticks, ticks);
    }

    @Override
    public void onDisable() {
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
    }

    public BloecoRuntime runtime() {
        return runtime;
    }

    private Money configuredPositiveMoney(String path, String defaultValue) {
        String configured = getConfig().getString(path, defaultValue);
        try {
            Money amount = Money.parse(configured);
            if (amount.minor() <= 0) throw new IllegalArgumentException("amount must be positive");
            return amount;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(path + " must be a positive amount with at most two decimals", exception);
        }
    }

    /** Keeps upgrades safe when a server previously ran the plugin under its old public name. */
    private void migrateLegacyDataFolder() {
        File target = getDataFolder();
        if (target.exists()) {
            return;
        }
        File legacy = new File(target.getParentFile(), "CentralEconomy");
        if (!legacy.isDirectory()) {
            return;
        }
        try {
            Files.move(legacy.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            getLogger().info("Migrated legacy CentralEconomy data to Bloeco.");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not migrate legacy CentralEconomy data folder to Bloeco", exception);
        }
    }

}
