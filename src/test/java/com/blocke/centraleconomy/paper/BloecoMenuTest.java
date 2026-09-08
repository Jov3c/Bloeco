package com.blocke.centraleconomy.paper;

import com.blocke.centraleconomy.economy.EconomyService;
import com.blocke.centraleconomy.economy.TaxPolicy;
import com.blocke.centraleconomy.economy.TaxType;
import com.blocke.centraleconomy.ledger.SqliteLedgerRepository;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BloecoMenuTest {
    @TempDir Path temporaryDirectory;
    private ServerMock server;
    private SqliteLedgerRepository ledger;
    @BeforeEach void setUp() { server = MockBukkit.mock(); ledger = new SqliteLedgerRepository(temporaryDirectory.resolve("economy.db")); }
    @AfterEach void tearDown() { ledger.close(); MockBukkit.unmock(); }

    @Test
    void hubContainsOnlyMonetaryPlayerActions() {
        PlayerMock player = server.addPlayer();
        BloecoMenu menu = menu(new TaxPolicy(1, 5), new TaxAdministratorAccess("example.tax", Set.of()), (type, rate) -> { });
        menu.open(player);
        assertNull(player.getOpenInventory().getTopInventory().getItem(10));
        assertNull(player.getOpenInventory().getTopInventory().getItem(12));
        assertNotNull(player.getOpenInventory().getTopInventory().getItem(14));
    }

    @Test
    void configuredTaxAdministratorChangesAndPersistsATaxRateFromTheGui() {
        PlayerMock administrator = server.addPlayer();
        TaxPolicy policy = new TaxPolicy(1, 5);
        AtomicInteger savedPercent = new AtomicInteger(-1);
        BloecoMenu menu = menu(policy, new TaxAdministratorAccess("example.tax", Set.of(administrator.getUniqueId())),
                (type, rate) -> { if (type == TaxType.TRANSFER_FEE) savedPercent.set(rate); });
        menu.open(administrator);
        menu.onInventoryClick(click(administrator, 16));
        menu.onInventoryClick(click(administrator, 10));
        menu.onInventoryClick(click(administrator, 11));
        assertEquals(2, policy.rate(TaxType.TRANSFER_FEE));
        assertEquals(2, savedPercent.get());
    }

    private BloecoMenu menu(TaxPolicy policy, TaxAdministratorAccess access, java.util.function.BiConsumer<TaxType, Integer> persistence) {
        Plugin plugin = MockBukkit.createMockPlugin();
        return new BloecoMenu(plugin, new EconomyService(ledger, policy), policy, access,
                UUID.fromString("00000000-0000-0000-0000-000000000098"), persistence);
    }
    private static InventoryClickEvent click(PlayerMock player, int rawSlot) {
        return new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER, rawSlot,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
    }
}
