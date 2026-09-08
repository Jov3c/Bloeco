package com.blocke.centraleconomy.paper;

import com.blocke.centraleconomy.application.AsyncEconomyFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.nio.file.Path;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BloecoMenuTest {
    @TempDir Path temporaryDirectory;
    private ServerMock server;
    private AsyncEconomyFacade economy;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        economy = AsyncEconomyFacade.sqlite(temporaryDirectory.resolve("economy.db"), Clock.systemUTC());
        assertTrue(economy.readyStage().toCompletableFuture().join().isSuccess());
    }

    @AfterEach
    void tearDown() {
        economy.close();
        MockBukkit.unmock();
    }

    @Test
    void ordinaryPlayerSeesOnlyBalanceAndTransfer() {
        var plugin = MockBukkit.createMockPlugin();
        var player = server.addPlayer();
        BloecoMenu menu = new BloecoMenu(plugin, economy, new RoleAccess());

        menu.open(player);

        assertNotNull(player.getOpenInventory().getTopInventory().getItem(4));
        assertNotNull(player.getOpenInventory().getTopInventory().getItem(14));
        assertNull(player.getOpenInventory().getTopInventory().getItem(16));
    }

    @Test
    void monetaryAdministratorSeesCentralBankPanel() {
        var plugin = MockBukkit.createMockPlugin();
        var player = server.addPlayer();
        player.addAttachment(plugin, RoleAccess.MONETARY, true);
        BloecoMenu menu = new BloecoMenu(plugin, economy, new RoleAccess());

        menu.open(player);

        assertNotNull(player.getOpenInventory().getTopInventory().getItem(16));
    }

    @Test
    void everyChildMenuProvidesBackAndHomeNavigation() {
        var plugin = MockBukkit.createMockPlugin();
        var player = server.addPlayer("Administrator");
        server.addPlayer("Recipient");
        player.addAttachment(plugin, RoleAccess.MONETARY, true);
        player.addAttachment(plugin, RoleAccess.TAX, true);
        player.addAttachment(plugin, RoleAccess.AUDITOR, true);
        BloecoMenu menu = new BloecoMenu(plugin, economy, new RoleAccess());

        menu.open(player);
        assertNotNull(player.getOpenInventory().getTopInventory().getItem(22));

        player.simulateInventoryClick(14);
        assertNavigation(player);
        player.simulateInventoryClick(0);
        assertNavigation(player);
        player.simulateInventoryClick(21);
        assertEquals("Bloeco 转账 - 选择玩家", player.getOpenInventory().getTitle());
        player.simulateInventoryClick(22);
        assertEquals("Bloeco 经济中心", player.getOpenInventory().getTitle());

        player.simulateInventoryClick(4);
        assertNavigation(player);
        player.simulateInventoryClick(21);
        assertEquals("Bloeco 经济中心", player.getOpenInventory().getTitle());

        player.simulateInventoryClick(16);
        assertNavigation(player);
        player.simulateInventoryClick(10);
        assertNavigation(player);
        player.simulateInventoryClick(21);
        assertEquals("Bloeco 中央银行", player.getOpenInventory().getTitle());

        player.simulateInventoryClick(12);
        assertNavigation(player);
        player.simulateInventoryClick(22);
        assertEquals("Bloeco 经济中心", player.getOpenInventory().getTitle());

        player.simulateInventoryClick(16);
        player.simulateInventoryClick(18);
        assertNavigation(player);
    }

    private static void assertNavigation(org.mockbukkit.mockbukkit.entity.PlayerMock player) {
        assertNotNull(player.getOpenInventory().getTopInventory().getItem(21), "missing back button");
        assertNotNull(player.getOpenInventory().getTopInventory().getItem(22), "missing home button");
    }
}
