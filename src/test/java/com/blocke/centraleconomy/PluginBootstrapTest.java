package com.blocke.centraleconomy;

import com.blocke.centraleconomy.domain.account.AccountId;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginBootstrapTest {

    @BeforeEach
    void setUpMockServer() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDownMockServer() {
        MockBukkit.unmock();
    }

    @Test
    void pluginLoadsWithDefaultResources() {
        CentralEconomyPlugin plugin = MockBukkit.load(CentralEconomyPlugin.class);

        assertNotNull(plugin);
        assertTrue(plugin.getDataFolder().toPath().resolve("config.yml").toFile().isFile());
        assertNotNull(plugin.getCommand("eco"));
        assertNull(plugin.getCommand("market"));
        assertFalse(plugin.getDataFolder().toPath().resolve("procurement.yml").toFile().exists());
        assertFalse(plugin.getDataFolder().toPath().resolve("market.yml").toFile().exists());
        assertTrue(plugin.runtime().readyStage().toCompletableFuture().join().isSuccess());
        assertEquals("1000000.00", plugin.getConfig().getString("bootstrap.treasury-initial-balance"));
        assertEquals("100.00", plugin.getConfig().getString("bootstrap.player-initial-balance"));
        assertEquals(100_000_000L, plugin.runtime().facade().balance(AccountId.treasury())
                .toCompletableFuture().join().value());
    }

    @Test
    void joiningPlayerReceivesConfiguredStarterFundsFromTreasury() {
        CentralEconomyPlugin plugin = MockBukkit.load(CentralEconomyPlugin.class);
        assertTrue(plugin.runtime().readyStage().toCompletableFuture().join().isSuccess());

        var player = MockBukkit.getMock().addPlayer("NewPlayer");

        assertEquals(10_000L, plugin.runtime().facade().playerBalance(player.getUniqueId())
                .toCompletableFuture().join().value());
        assertEquals(99_990_000L, plugin.runtime().facade().balance(AccountId.treasury())
                .toCompletableFuture().join().value());
    }

    @Test
    void pluginIsStandaloneAndContainsNoCommerceOrVaultMetadata() {
        var plugin = MockBukkit.load(CentralEconomyPlugin.class);

        assertEquals(java.util.Set.of("eco", "pay"), plugin.getDescription().getCommands().keySet());
        assertNotNull(plugin.getCommand("eco"));
        assertNotNull(plugin.getCommand("pay"));
        assertNull(plugin.getCommand("bloeco"));
        assertNull(plugin.getCommand("market"));
        assertFalse(plugin.getDescription().getSoftDepend().contains("Vault"));
        assertFalse(plugin.getDescription().getLoadBefore().contains("Bloeco-Stock"));
    }

    @Test
    void payAcceptsPositiveIntegersOnly() {
        CentralEconomyPlugin plugin = MockBukkit.load(CentralEconomyPlugin.class);
        assertTrue(plugin.runtime().readyStage().toCompletableFuture().join().isSuccess());
        var payer = MockBukkit.getMock().addPlayer("Payer");
        MockBukkit.getMock().addPlayer("Receiver");

        payer.performCommand("pay Receiver 1.5");

        payer.assertSaid("金额必须是大于零的整数。");
    }

    @Test
    void selfPaymentReturnsAUserMessageWithoutThrowingACommandException() {
        CentralEconomyPlugin plugin = MockBukkit.load(CentralEconomyPlugin.class);
        assertTrue(plugin.runtime().readyStage().toCompletableFuture().join().isSuccess());
        var payer = MockBukkit.getMock().addPlayer("Payer");

        assertDoesNotThrow(() -> payer.performCommand("pay Payer 11"));

        payer.assertSaid("不能给自己转账。");
    }

    @Test
    void payCompletesOnlinePlayersAndCommonIntegerAmounts() {
        CentralEconomyPlugin plugin = MockBukkit.load(CentralEconomyPlugin.class);
        var payer = MockBukkit.getMock().addPlayer("Payer");
        MockBukkit.getMock().addPlayer("Receiver");

        var players = plugin.getCommand("pay").tabComplete(payer, "pay", new String[]{"R"});
        var amounts = plugin.getCommand("pay").tabComplete(payer, "pay", new String[]{"Receiver", ""});

        assertEquals(java.util.List.of("Receiver"), players);
        assertEquals(java.util.List.of("1", "10", "100", "1000"), amounts);
    }
}
