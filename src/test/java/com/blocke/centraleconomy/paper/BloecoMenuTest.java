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
}
