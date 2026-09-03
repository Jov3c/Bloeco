package com.blocke.centraleconomy;

import com.blocke.centraleconomy.economy.EconomyService;
import com.blocke.centraleconomy.economy.ProcurementItem;
import com.blocke.centraleconomy.ledger.SqliteLedgerRepository;
import com.blocke.centraleconomy.market.MarketService;
import com.blocke.centraleconomy.market.SqliteMarketRepository;
import com.blocke.centraleconomy.money.Money;
import com.blocke.centraleconomy.paper.EconomyCommand;
import com.blocke.centraleconomy.paper.MarketCommand;
import com.blocke.centraleconomy.paper.MarketMenu;
import com.blocke.centraleconomy.paper.PayCommand;
import com.blocke.centraleconomy.paper.ProcurementMenu;
import com.blocke.centraleconomy.vault.CentralEconomyVaultProvider;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.ServicePriority;
import net.milkbowl.vault.economy.Economy;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class CentralEconomyPlugin extends JavaPlugin {
    private SqliteLedgerRepository ledger;
    private CentralEconomyVaultProvider vaultProvider;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("procurement.yml", false);
        saveResource("market.yml", false);

        ledger = new SqliteLedgerRepository(getDataFolder().toPath().resolve(getConfig().getString("database-file", "economy.db")));
        EconomyService economy = new EconomyService(ledger, procurementTaxPercent(), transferFeePercent(), transferIncomeTaxPercent());
        ProcurementMenu menu = new ProcurementMenu(this, economy, loadProcurementItems());
        getCommand("economy").setExecutor(new EconomyCommand(economy, menu, blockStockReserveTreasury()));
        getCommand("pay").setExecutor(new PayCommand(economy));
        MarketService market = new MarketService(ledger, new SqliteMarketRepository(ledger), marketFeePercent(), consumptionTaxPercent());
        MarketMenu marketMenu = new MarketMenu(this, market);
        getCommand("market").setExecutor(new MarketCommand(marketMenu));
        registerVaultProvider();
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        vaultProvider = null;
        if (ledger != null) {
            ledger.close();
            ledger = null;
        }
    }

    private void registerVaultProvider() {
        var vault = getServer().getPluginManager().getPlugin("Vault");
        if (vault == null || !vault.isEnabled()) {
            return;
        }
        String singular = getConfig().getString("currency.singular", "金币");
        String plural = getConfig().getString("currency.plural", singular);
        vaultProvider = new CentralEconomyVaultProvider(ledger, singular, plural);
        getServer().getServicesManager().register(Economy.class, vaultProvider, this, ServicePriority.Highest);
        getLogger().info("Registered CentralEconomy with Vault.");
    }

    private int procurementTaxPercent() {
        return wholePercent("procurement-income-tax-rate", 0.05d, 100, "procurement income tax");
    }

    private int transferFeePercent() {
        return wholePercent("transfer-fee-rate", 0.01d, 100, "transfer fee");
    }

    private int transferIncomeTaxPercent() {
        return wholePercent("transfer-income-tax-rate", 0.05d, 100, "transfer income tax");
    }

    private int consumptionTaxPercent() {
        return wholePercent("market-consumption-tax-rate", 0.03d, 100, "market consumption tax");
    }

    private int wholePercent(String path, double defaultRate, int maximum, String label) {
        double configuredRate = getConfig().getDouble(path, defaultRate);
        int percentage = (int) Math.round(configuredRate * 100.0d);
        if (percentage < 0 || percentage > maximum || Math.abs(configuredRate - percentage / 100.0d) > 0.0000001d) {
            throw new IllegalArgumentException(path + " must be a whole percentage from 0 through " + maximum + " expressed as a fraction");
        }
        return percentage;
    }

    private int marketFeePercent() {
        File marketFile = new File(getDataFolder(), getConfig().getString("market-file", "market.yml"));
        double configuredRate = YamlConfiguration.loadConfiguration(marketFile).getDouble("fee-rate", 0.02d);
        int percentage = (int) Math.round(configuredRate * 100.0d);
        if (percentage < 0 || percentage > 20 || Math.abs(configuredRate - percentage / 100.0d) > 0.0000001d) {
            throw new IllegalArgumentException("market fee-rate must be a whole percentage from 0.00 to 0.20");
        }
        return percentage;
    }

    private UUID blockStockReserveTreasury() {
        String raw = getConfig().getString("blockstock.bluechip-reserve-treasury-uuid",
                "00000000-0000-0000-0000-000000000098");
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("blockstock.bluechip-reserve-treasury-uuid must be a UUID", exception);
        }
    }

    private List<ProcurementItem> loadProcurementItems() {
        File procurementFile = new File(getDataFolder(), "procurement.yml");
        ConfigurationSection items = YamlConfiguration.loadConfiguration(procurementFile).getConfigurationSection("items");
        if (items == null) {
            return List.of();
        }
        List<ProcurementItem> loaded = new ArrayList<>();
        for (String key : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(key);
            if (item == null) {
                continue;
            }
            String materialName = item.getString("material", key);
            Material material = Material.matchMaterial(materialName.toUpperCase(Locale.ROOT).replace("MINECRAFT:", ""));
            if (material == null || !material.isItem()) {
                getLogger().warning("Ignoring procurement item with invalid material: " + materialName);
                continue;
            }
            long unitPrice = item.getLong("unit-price-cents", 0L);
            int maximum = item.getInt("max-per-sale", 0);
            boolean enabled = item.getBoolean("enabled", true);
            try {
                loaded.add(new ProcurementItem("minecraft:" + material.key().value(), Money.ofCents(unitPrice), maximum, enabled));
            } catch (IllegalArgumentException exception) {
                getLogger().warning("Ignoring invalid procurement item " + key + ": " + exception.getMessage());
            }
        }
        return List.copyOf(loaded);
    }
}
