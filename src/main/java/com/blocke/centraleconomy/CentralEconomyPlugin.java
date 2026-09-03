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

public class CentralEconomyPlugin extends JavaPlugin {
    private SqliteLedgerRepository ledger;
    private CentralEconomyVaultProvider vaultProvider;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("procurement.yml", false);
        saveResource("market.yml", false);

        ledger = new SqliteLedgerRepository(getDataFolder().toPath().resolve(getConfig().getString("database-file", "economy.db")));
        EconomyService economy = new EconomyService(ledger, procurementTaxPercent());
        ProcurementMenu menu = new ProcurementMenu(this, economy, loadProcurementItems());
        getCommand("economy").setExecutor(new EconomyCommand(economy, menu));
        MarketService market = new MarketService(ledger, new SqliteMarketRepository(ledger), marketFeePercent());
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
        double configuredRate = getConfig().getDouble("procurement-income-tax-rate", 0.05d);
        int percentage = (int) Math.round(configuredRate * 100.0d);
        if (percentage < 0 || percentage > 100 || Math.abs(configuredRate - percentage / 100.0d) > 0.0000001d) {
            throw new IllegalArgumentException("procurement-income-tax-rate must be a whole percentage expressed as a fraction");
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
