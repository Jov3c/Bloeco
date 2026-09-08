package com.blocke.centraleconomy;

import com.blocke.centraleconomy.paper.BloecoRuntime;
import com.blocke.centraleconomy.paper.BloecoMenu;
import com.blocke.centraleconomy.paper.EcoCommand;
import com.blocke.centraleconomy.paper.PayCommand;
import com.blocke.centraleconomy.paper.RoleAccess;
import com.blocke.centraleconomy.paper.PlayerStarterFundsListener;
import com.blocke.centraleconomy.domain.money.Money;
import com.blocke.centraleconomy.domain.banking.BankingPolicy;
import com.blocke.centraleconomy.storage.redis.RedisEconomyBridge;
import com.blocke.centraleconomy.storage.mysql.MySqlOutboxPublisher;
import java.time.Duration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
public class CentralEconomyPlugin extends JavaPlugin {
    private BloecoRuntime runtime;
    private RedisEconomyBridge redisBridge;
    private MySqlOutboxPublisher outboxPublisher;

    @Override
    public void onEnable() {
        migrateLegacyDataFolder();
        saveDefaultConfig();
        String storageType = getConfig().getString("storage.type", "mysql");
        // The Gradle test task sets this explicit local-only override; production always follows config.yml.
        String testStorage = System.getProperty("bloeco.test.storage");
        if (testStorage != null && !testStorage.isBlank()) storageType = testStorage;
        Money initialTreasury = configuredPositiveMoney(
                "bootstrap.treasury-initial-balance", "1000000.00");
        Money initialPlayerBalance = configuredPositiveMoney(
                "bootstrap.player-initial-balance", "100.00");
        Money bankCapital = configuredPositiveMoney("bank.initial-capital", "250000.00");
        BankingPolicy bankPolicy = configuredBankPolicy();
        runtime = createRuntime(storageType, initialTreasury, bankCapital, bankPolicy);
        connectRedisIfEnabled(storageType);
        RoleAccess roles = new RoleAccess();
        BloecoMenu menu = new BloecoMenu(this, runtime.facade(), runtime.banking(), roles);
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
        if (outboxPublisher != null) {
            outboxPublisher.close();
            outboxPublisher = null;
        }
        if (redisBridge != null) {
            redisBridge.close();
            redisBridge = null;
        }
    }

    public BloecoRuntime runtime() {
        return runtime;
    }

    private BloecoRuntime createRuntime(String storageType, Money initialTreasury,
                                        Money bankCapital, BankingPolicy bankPolicy) {
        if ("sqlite".equalsIgnoreCase(storageType)) {
            return BloecoRuntime.sqlite(getDataFolder().toPath().resolve(
                    getConfig().getString("storage.sqlite.file", "economy.db")),
                    initialTreasury, bankCapital, bankPolicy);
        }
        if (!"mysql".equalsIgnoreCase(storageType)) {
            throw new IllegalArgumentException("storage.type must be mysql or sqlite");
        }
        String jdbcUrl = getConfig().getString("storage.mysql.jdbc-url");
        String username = getConfig().getString("storage.mysql.username");
        String password = resolveSecret("storage.mysql.password", "storage.mysql.password-env");
        int poolSize = Math.max(2, getConfig().getInt("storage.mysql.maximum-pool-size", 16));
        if (jdbcUrl == null || jdbcUrl.isBlank() || username == null || username.isBlank()) {
            throw new IllegalArgumentException("storage.mysql.jdbc-url and username are required");
        }
        return BloecoRuntime.mysql(jdbcUrl, username, password, poolSize,
                initialTreasury, bankCapital, bankPolicy);
    }

    private BankingPolicy configuredBankPolicy() {
        return new BankingPolicy(
                configuredBasisPoints("bank.deposit-rate-bps", 100),
                configuredBasisPoints("bank.loan-rate-bps", 500),
                configuredBasisPoints("bank.reserve-ratio-bps", 2000),
                configuredPositiveMoney("bank.maximum-loan", "10000.00"),
                getConfig().getBoolean("bank.lending-enabled", true),
                Math.max(1, getConfig().getInt("bank.loan-term-days", 7)));
    }

    private int configuredBasisPoints(String path, int defaultValue) {
        int value = getConfig().getInt(path, defaultValue);
        if (value < 0 || value > 10_000) throw new IllegalArgumentException(path + " must be 0..10000");
        return value;
    }

    private String resolveSecret(String valuePath, String envPath) {
        String environmentName = getConfig().getString(envPath, "");
        if (environmentName != null && !environmentName.isBlank()) {
            String environmentValue = System.getenv(environmentName);
            if (environmentValue != null) return environmentValue;
        }
        return getConfig().getString(valuePath, "");
    }

    private void connectRedisIfEnabled(String storageType) {
        if (!getConfig().getBoolean("redis.enabled", true)) return;
        try {
            redisBridge = RedisEconomyBridge.connect(
                    getConfig().getString("redis.uri", "redis://127.0.0.1:6379/0"),
                    getConfig().getString("redis.key-prefix", "bloeco:v2:"),
                    getConfig().getString("redis.stream", "bloeco:v2:ledger-events"),
                    getConfig().getLong("redis.cache-ttl-seconds", 60L));
            getLogger().info("Redis accelerator connected; MySQL remains the ledger authority.");
            if ("mysql".equalsIgnoreCase(storageType)) {
                String jdbcUrl = getConfig().getString("storage.mysql.jdbc-url");
                String username = getConfig().getString("storage.mysql.username");
                String password = resolveSecret("storage.mysql.password", "storage.mysql.password-env");
                outboxPublisher = new MySqlOutboxPublisher(jdbcUrl, username, password,
                        redisBridge, Duration.ofSeconds(2));
            }
        } catch (RuntimeException exception) {
            getLogger().warning("Redis unavailable; continuing with MySQL authority only.");
        }
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
