package com.blocke.centraleconomy;

import org.bukkit.configuration.ConfigurationSection;
import com.blocke.centraleconomy.paper.BloecoRuntime;
import com.blocke.centraleconomy.paper.BloecoMenu;
import com.blocke.centraleconomy.paper.EcoCommand;
import com.blocke.centraleconomy.paper.PayCommand;
import com.blocke.centraleconomy.paper.RoleAccess;
import com.blocke.centraleconomy.paper.PlayerStarterFundsListener;
import com.blocke.centraleconomy.paper.BloecoApiProvider;
import com.blocke.centraleconomy.paper.PaperClusterRuntime;
import com.blocke.centraleconomy.paper.ClusterPaymentTargetResolver;
import com.blocke.centraleconomy.cluster.ClusterSettings;
import com.blocke.centraleconomy.storage.redis.RedisClusterCoordinator;
import com.blocke.bloeco.api.BloecoApi;
import com.blocke.centraleconomy.domain.money.Money;
import com.blocke.centraleconomy.domain.banking.BankingPolicy;
import com.blocke.centraleconomy.storage.redis.RedisEconomyBridge;
import com.blocke.centraleconomy.storage.mysql.MySqlOutboxPublisher;
import com.blocke.centraleconomy.config.ConfigurationSnapshot;
import com.blocke.centraleconomy.config.ConfigurationValidator;
import java.time.Duration;
import java.time.Clock;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.ServicePriority;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
public class CentralEconomyPlugin extends JavaPlugin {
    private BloecoRuntime runtime;
    private RedisEconomyBridge redisBridge;
    private MySqlOutboxPublisher outboxPublisher;
    private BloecoApiProvider apiProvider;
    private PaperClusterRuntime clusterRuntime;

    @Override
    public void onEnable() {
        migrateLegacyDataFolder();
        saveDefaultConfig();
        Money initialTreasury = configuredPositiveMoney(
                "bootstrap.treasury-initial-balance", "1000000.00");
        Money initialPlayerBalance = configuredPositiveMoney(
                "bootstrap.player-initial-balance", "100.00");
        Money bankCapital = configuredPositiveMoney("bank.initial-capital", "250000.00");
        BankingPolicy bankPolicy = configuredBankPolicy();
        ConfigurationSnapshot config = validatedConfiguration(initialTreasury, initialPlayerBalance, bankCapital, bankPolicy);
        ClusterSettings cluster = configuredCluster(config);
        runtime = createRuntime(config, initialTreasury, bankCapital, bankPolicy, cluster != null);
        connectRedisIfEnabled(config);
        RoleAccess roles = new RoleAccess();
        BloecoMenu menu = new BloecoMenu(this, runtime.facade(), runtime.banking(), roles);
        PayCommand pay = createPayCommand(cluster);
        getCommand("pay").setExecutor(pay);
        getCommand("pay").setTabCompleter(pay);
        getCommand("eco").setExecutor(new EcoCommand(menu));
        getServer().getPluginManager().registerEvents(
                new PlayerStarterFundsListener(this, runtime.facade(), initialPlayerBalance), this);
        long ticks = Math.multiplyExact(config.integrityIntervalMinutes(), 1_200L);
        getServer().getScheduler().runTaskTimer(this, () -> runtime.verifyNow().thenAccept(result -> {
            if (!result.isSuccess()) {
                getLogger().warning("Ledger integrity check could not complete: " + result.message());
            } else if (!result.value().valid()) {
                getLogger().severe("Central ledger integrity check failed; Bloeco is now read-only.");
            }
        }), ticks, ticks);
        apiProvider = new BloecoApiProvider(runtime.institutions());
        getServer().getServicesManager().register(BloecoApi.class, apiProvider, this, ServicePriority.Normal);
        runtime.readyStage().thenAccept(result -> {
            if (result.isSuccess()) getLogger().info("Bloeco ledger is ready.");
            else getLogger().severe("Bloeco ledger could not start: " + result.message());
        });
    }

    @Override
    public void onDisable() {
        if (clusterRuntime != null) {
            clusterRuntime.close();
            clusterRuntime = null;
        }
        if (apiProvider != null) {
            getServer().getServicesManager().unregister(apiProvider);
            apiProvider = null;
        }
        if (outboxPublisher != null) {
            outboxPublisher.close();
            outboxPublisher = null;
        }
        if (redisBridge != null) {
            redisBridge.close();
            redisBridge = null;
        }
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
    }

    public BloecoRuntime runtime() {
        return runtime;
    }

    private BloecoRuntime createRuntime(ConfigurationSnapshot config, Money initialTreasury,
                                        Money bankCapital, BankingPolicy bankPolicy, boolean clustered) {
        if ("sqlite".equals(config.storageType())) {
            return BloecoRuntime.sqlite(getDataFolder().toPath().resolve(
                    getConfig().getString("storage.sqlite.file", "economy.db")),
                    initialTreasury, bankCapital, bankPolicy,
                    Math.max(8, getConfig().getInt("ecosystem-api.queue-capacity", 256)));
        }
        String password = resolveSecret("storage.mysql.password", "storage.mysql.password-env");
        int workers = Math.max(2, getConfig().getInt("ecosystem-api.mysql-workers", 4));
        int queue = Math.max(8, getConfig().getInt("ecosystem-api.queue-capacity", 256));
        return BloecoRuntime.mysql(config.mysqlJdbcUrl(), config.mysqlUsername(), password, config.mysqlPoolSize(),
                initialTreasury, bankCapital, bankPolicy, workers, queue, clustered);
    }

    private BankingPolicy configuredBankPolicy() {
        return new BankingPolicy(
                configuredBasisPoints("bank.deposit-rate-bps", 100),
                configuredBasisPoints("bank.loan-rate-bps", 500),
                configuredBasisPoints("bank.reserve-ratio-bps", 2000),
                configuredPositiveMoney("bank.maximum-loan", "10000.00"),
                getConfig().getBoolean("bank.lending-enabled", false),
                Math.max(1, getConfig().getInt("bank.loan-term-days", 7)));
    }

    static boolean redisEnabled(ConfigurationSection config) {
        // Older installations enabled Redis when the on-disk setting was absent.
        return !config.contains("redis.enabled", true) || config.getBoolean("redis.enabled");
    }

    private ConfigurationSnapshot validatedConfiguration(Money treasury,
                                              Money playerBalance, Money bankCapital,
                                              BankingPolicy bankPolicy) {
        ConfigurationSnapshot snapshot = new ConfigurationSnapshot(
                getConfig().getString("storage.type", "mysql"),
                getConfig().getString("storage.mysql.jdbc-url", ""),
                getConfig().getString("storage.mysql.username", ""),
                redisEnabled(getConfig()),
                getConfig().getString("redis.uri", ""),
                getConfig().getInt("storage.mysql.maximum-pool-size", 16),
                getConfig().getLong("redis.cache-ttl-seconds", 60L),
                treasury.minor(), playerBalance.minor(), bankCapital.minor(),
                0, 0, 0,
                bankPolicy.depositRateBasisPoints(), bankPolicy.loanRateBasisPoints(),
                bankPolicy.reserveRatioBasisPoints(), bankPolicy.maximumLoan().minor(),
                bankPolicy.loanTermDays(),
                getConfig().getLong("integrity.check-interval-minutes", 15L),
                getConfig().getLong("redis.reconnect-interval-seconds", 10L));
        var errors = ConfigurationValidator.validate(snapshot);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("配置校验失败：" + String.join("；", errors));
        }
        return snapshot;
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

    private void connectRedisIfEnabled(ConfigurationSnapshot config) {
        if (!"mysql".equals(config.storageType()) || !config.redisEnabled()) return;
        try {
            redisBridge = RedisEconomyBridge.connect(
                    config.redisUri(),
                    getConfig().getString("redis.key-prefix", "bloeco:v2:"),
                    getConfig().getString("redis.stream", "bloeco:v2:ledger-events"),
                    config.redisTtlSeconds(), config.redisReconnectSeconds());
            if (redisBridge.isAvailable()) {
                getLogger().info("Redis accelerator connected; MySQL remains the ledger authority.");
            } else {
                getLogger().warning("Redis unavailable; Bloeco will reconnect automatically while MySQL remains available.");
            }
            outboxPublisher = runtime.createOutboxPublisher(
                    redisBridge, Duration.ofSeconds(2), getLogger()::warning);
        } catch (RuntimeException exception) {
            getLogger().warning("Redis unavailable; continuing with MySQL authority only.");
        }
    }

    private ClusterSettings configuredCluster(ConfigurationSnapshot config) {
        if (!getConfig().getBoolean("cluster.enabled", false)) return null;
        if (!"mysql".equals(config.storageType()) || !config.redisEnabled()) {
            throw new IllegalArgumentException("cluster.enabled requires MySQL storage and Redis");
        }
        return new ClusterSettings(true,
                getConfig().getString("cluster.cluster-id", "production"),
                getConfig().getString("cluster.server-id", "server-01"),
                Duration.ofSeconds(getConfig().getLong("cluster.heartbeat-seconds", 10L)),
                Duration.ofSeconds(getConfig().getLong("cluster.lease-seconds", 30L)),
                getConfig().getString("cluster.consumer-group-prefix", "bloeco:v2:cluster:"));
    }

    private PayCommand createPayCommand(ClusterSettings settings) {
        if (settings == null) return new PayCommand(this, runtime.facade());
        if (redisBridge == null) throw new IllegalStateException("Could not initialize Redis for cross-server mode");
        RedisClusterCoordinator coordinator = new RedisClusterCoordinator(redisBridge, settings, Clock.systemUTC());
        clusterRuntime = new PaperClusterRuntime(this, settings, runtime.clusterDirectory(), coordinator);
        getLogger().info("Cross-server economy synchronization enabled as " + settings.serverId() + ".");
        return new PayCommand(this, runtime.facade(),
                new ClusterPaymentTargetResolver(runtime.clusterDirectory()), false);
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
