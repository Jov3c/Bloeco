package com.blocke.centraleconomy;

import com.blocke.centraleconomy.economy.EconomyService;
import com.blocke.centraleconomy.economy.TaxPolicy;
import com.blocke.centraleconomy.economy.TaxType;
import com.blocke.centraleconomy.ledger.SqliteLedgerRepository;
import com.blocke.centraleconomy.paper.BloecoCommand;
import com.blocke.centraleconomy.paper.BloecoMenu;
import com.blocke.centraleconomy.paper.PayCommand;
import com.blocke.centraleconomy.paper.TaxAdministratorAccess;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

public class CentralEconomyPlugin extends JavaPlugin {
    private SqliteLedgerRepository ledger;

    @Override
    public void onEnable() {
        migrateLegacyDataFolder();
        saveDefaultConfig();
        ledger = new SqliteLedgerRepository(getDataFolder().toPath().resolve(
                getConfig().getString("storage.sqlite.file", "economy.db")));
        TaxPolicy taxPolicy = new TaxPolicy(transferFeePercent(), transferIncomeTaxPercent());
        EconomyService economy = new EconomyService(ledger, taxPolicy);
        getCommand("pay").setExecutor(new PayCommand(economy));
        BloecoMenu bloecoMenu = new BloecoMenu(this, economy, taxPolicy,
                taxAdministratorAccess(), this::persistTaxRate);
        getCommand("bloeco").setExecutor(new BloecoCommand(bloecoMenu));
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (ledger != null) {
            ledger.close();
            ledger = null;
        }
    }

    private int transferFeePercent() {
        return wholePercent("transfer-fee-rate", 0.01d, 100, "transfer fee");
    }

    private int transferIncomeTaxPercent() {
        return wholePercent("transfer-income-tax-rate", 0.05d, 100, "transfer income tax");
    }

    private int wholePercent(String path, double defaultRate, int maximum, String label) {
        double configuredRate = getConfig().getDouble(path, defaultRate);
        int percentage = (int) Math.round(configuredRate * 100.0d);
        if (percentage < 0 || percentage > maximum || Math.abs(configuredRate - percentage / 100.0d) > 0.0000001d) {
            throw new IllegalArgumentException(path + " must be a whole percentage from 0 through " + maximum + " expressed as a fraction");
        }
        return percentage;
    }

    private TaxAdministratorAccess taxAdministratorAccess() {
        String permission = getConfig().getString("tax-administrators.permission", "bloeco.tax-admin");
        List<String> configuredIds = getConfig().getStringList("tax-administrators.player-uuids");
        java.util.Set<UUID> ids = new java.util.LinkedHashSet<>();
        for (String raw : configuredIds) {
            try {
                ids.add(UUID.fromString(raw));
            } catch (IllegalArgumentException exception) {
                getLogger().warning("Ignoring invalid tax administrator UUID: " + raw);
            }
        }
        return new TaxAdministratorAccess(permission, ids);
    }

    private void persistTaxRate(TaxType type, Integer percentage) {
        getConfig().set(type.configPath(), percentage / 100.0d);
        saveConfig();
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
