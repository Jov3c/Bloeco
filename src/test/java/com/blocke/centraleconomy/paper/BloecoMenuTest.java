package com.blocke.centraleconomy.paper;

import com.blocke.centraleconomy.economy.EconomyService;
import com.blocke.centraleconomy.economy.ProcurementItem;
import com.blocke.centraleconomy.economy.TaxPolicy;
import com.blocke.centraleconomy.economy.TaxType;
import com.blocke.centraleconomy.ledger.SqliteLedgerRepository;
import com.blocke.centraleconomy.market.MarketService;
import com.blocke.centraleconomy.market.SqliteMarketRepository;
import com.blocke.centraleconomy.money.Money;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BloecoMenuTest {
    @TempDir Path temporaryDirectory;
    private ServerMock server;
    private SqliteLedgerRepository ledger;

    @BeforeEach
    void setUp() { server = MockBukkit.mock(); ledger = new SqliteLedgerRepository(temporaryDirectory.resolve("economy.db")); }

    @AfterEach
    void tearDown() { ledger.close(); MockBukkit.unmock(); }

    @Test
    void configuredTaxAdministratorChangesAndPersistsATaxRateFromTheGui() {
        PlayerMock administrator = server.addPlayer();
        TaxPolicy policy = new TaxPolicy(5, 1, 5, 3);
        AtomicInteger savedPercent = new AtomicInteger(-1);
        BloecoMenu menu = menu(policy, new TaxAdministratorAccess("example.tax", Set.of(administrator.getUniqueId())),
                (type, rate) -> { if (type == TaxType.PROCUREMENT_INCOME) savedPercent.set(rate); });

        menu.open(administrator);
        menu.onInventoryClick(click(administrator, 16, ClickType.LEFT));
        menu.onInventoryClick(click(administrator, 10, ClickType.LEFT));
        menu.onInventoryClick(click(administrator, 10, ClickType.LEFT));

        assertEquals(6, policy.rate(TaxType.PROCUREMENT_INCOME));
        assertEquals(6, savedPercent.get());
        assertTrue(administrator.getOpenInventory().getTitle().contains("税率"));
    }

    private BloecoMenu menu(TaxPolicy policy, TaxAdministratorAccess access,
                            java.util.function.BiConsumer<TaxType, Integer> persistence) {
        Plugin plugin = MockBukkit.createMockPlugin();
        EconomyService economy = new EconomyService(ledger, policy);
        ProcurementMenu procurement = new ProcurementMenu(plugin, economy, List.of(
                new ProcurementItem("minecraft:wheat", Money.ofCents(100), 64, true)));
        MarketMenu marketMenu = new MarketMenu(plugin, new MarketService(ledger,
                new SqliteMarketRepository(ledger), 2, policy));
        return new BloecoMenu(plugin, economy, new MarketService(ledger, new SqliteMarketRepository(ledger), 2, policy),
                procurement, marketMenu, policy, access,
                UUID.fromString("00000000-0000-0000-0000-000000000098"), persistence);
    }

    private static InventoryClickEvent click(PlayerMock player, int rawSlot, ClickType click) {
        return new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER, rawSlot,
                click, InventoryAction.PICKUP_ALL);
    }
}
