package com.blocke.centraleconomy.paper;

import com.blocke.centraleconomy.application.AsyncEconomyFacade;
import com.blocke.centraleconomy.application.banking.AsyncBankingFacade;
import com.blocke.centraleconomy.domain.banking.BankingPolicy;
import com.blocke.centraleconomy.domain.money.Money;
import com.blocke.centraleconomy.storage.sqlite.SqliteBankingStore;
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
        assertEquals("自定义金额", displayName(player, 16));
        player.simulateInventoryClick(16);
        submitAmount(menu, player, "取消");
        server.getScheduler().performTicks(2);
        assertEquals("Bloeco 转账 - Recipient", player.getOpenInventory().getTitle());
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

    @Test
    @SuppressWarnings("deprecation")
    void issuanceAndRetirementExposeThreeVisiblePresetAmounts() {
        var plugin = MockBukkit.createMockPlugin();
        var player = server.addPlayer("MonetaryAdministrator");
        player.addAttachment(plugin, RoleAccess.MONETARY, true);
        BloecoMenu menu = new BloecoMenu(plugin, economy, new RoleAccess());

        menu.open(player);
        player.simulateInventoryClick(16);
        player.simulateInventoryClick(12);

        assertEquals("Bloeco 选择发行金额", player.getOpenInventory().getTitle());
        assertEquals("发行 100.00 金币", displayName(player, 10));
        assertEquals("发行 1000.00 金币", displayName(player, 12));
        assertEquals("发行 10000.00 金币", displayName(player, 14));

        player.simulateInventoryClick(21);
        player.simulateInventoryClick(14);

        assertEquals("Bloeco 选择回收金额", player.getOpenInventory().getTitle());
        assertEquals("回收 100.00 金币", displayName(player, 10));
        assertEquals("回收 1000.00 金币", displayName(player, 12));
        assertEquals("回收 10000.00 金币", displayName(player, 14));
    }

    @Test
    @SuppressWarnings("deprecation")
    void ecoContainsPlayerBankAndPermissionProtectedBankAdministration() {
        Path database = temporaryDirectory.resolve("bank-menu.db");
        Clock clock = Clock.systemUTC();
        try (AsyncEconomyFacade bankEconomy = AsyncEconomyFacade.sqlite(
                database, clock, Money.parse("1000000"));
             AsyncBankingFacade bank = new AsyncBankingFacade(
                     () -> new SqliteBankingStore(database, clock), bankEconomy.readyStage(),
                     Money.parse("250000"),
                     new BankingPolicy(100, 320, 2000, Money.parse("10000"), true, 7))) {
            assertTrue(bank.readyStage().toCompletableFuture().join().isSuccess());
            var plugin = MockBukkit.createMockPlugin();
            var player = server.addPlayer("BankCustomer");
            BloecoMenu menu = new BloecoMenu(plugin, bankEconomy, bank, new RoleAccess());
            bank.bankSnapshot().toCompletableFuture().join();

            menu.open(player);
            assertEquals("国有银行", displayName(player, 12));
            player.simulateInventoryClick(12);
            assertEquals("Bloeco 国有银行", player.getOpenInventory().getTitle());
            assertNavigation(player);
            assertTrue(player.getOpenInventory().getTopInventory().getItem(14).getItemMeta().getLore()
                    .contains("贷款年化利率：3.2%"));
            assertEquals("信用等级", displayName(player, 18));
            assertTrue(player.getOpenInventory().getTopInventory().getItem(18).getItemMeta().getLore()
                    .contains("A级：无未结清贷款"));
            player.simulateInventoryClick(4);
            assertEquals("Bloeco 银行账户详情", player.getOpenInventory().getTitle());
            assertNavigation(player);
            player.simulateInventoryClick(21);
            player.simulateInventoryClick(14);
            assertEquals("Bloeco 银行 - 贷款", player.getOpenInventory().getTitle());
            assertTrue(player.getOpenInventory().getTopInventory().getItem(10).getItemMeta().getLore()
                    .contains("预计应还：100.06"));
            assertEquals("自定义金额", displayName(player, 18));
            player.simulateInventoryClick(18);
            submitAmount(menu, player, "取消");
            server.getScheduler().performTicks(2);
            assertEquals("Bloeco 国有银行", player.getOpenInventory().getTitle());
            player.simulateInventoryClick(22);
            player.simulateInventoryClick(12);
            player.simulateInventoryClick(10);
            assertEquals("Bloeco 银行 - 存款", player.getOpenInventory().getTitle());
            assertNavigation(player);
            player.simulateInventoryClick(22);

            player.addAttachment(plugin, RoleAccess.BANKER, true);
            menu.open(player);
            player.simulateInventoryClick(16);
            assertEquals("银行管理", displayName(player, 20));
            player.simulateInventoryClick(20);
            assertEquals("Bloeco 银行管理", player.getOpenInventory().getTitle());
            assertNavigation(player);
        }
    }

    @Test
    void customLoanAmountEnteredInChatIsSubmitted() {
        Path database = temporaryDirectory.resolve("custom-bank-amount.db");
        Clock clock = Clock.systemUTC();
        try (AsyncEconomyFacade bankEconomy = AsyncEconomyFacade.sqlite(
                database, clock, Money.parse("1000000"));
             AsyncBankingFacade bank = new AsyncBankingFacade(
                     () -> new SqliteBankingStore(database, clock), bankEconomy.readyStage(),
                     Money.parse("250000"),
                     new BankingPolicy(100, 320, 2000, Money.parse("10000"), true, 7))) {
            assertTrue(bank.readyStage().toCompletableFuture().join().isSuccess());
            var plugin = MockBukkit.createMockPlugin();
            var player = server.addPlayer("CustomBorrower");
            BloecoMenu menu = new BloecoMenu(plugin, bankEconomy, bank, new RoleAccess());
            bank.bankSnapshot().toCompletableFuture().join();

            menu.open(player);
            player.simulateInventoryClick(12);
            player.simulateInventoryClick(14);
            player.simulateInventoryClick(18);
            submitAmount(menu, player, "123.45");
            server.getScheduler().performTicks(2);

            var snapshot = bank.playerSnapshot(player.getUniqueId()).toCompletableFuture().join().value();
            assertEquals(Money.parse("123.45"), snapshot.wallet());
            assertEquals(Money.parse("123.52"), snapshot.loanDebt());
        }
    }

    @SuppressWarnings("deprecation")
    private static String displayName(org.mockbukkit.mockbukkit.entity.PlayerMock player, int slot) {
        return player.getOpenInventory().getTopInventory().getItem(slot).getItemMeta().getDisplayName();
    }

    private static void assertNavigation(org.mockbukkit.mockbukkit.entity.PlayerMock player) {
        assertNotNull(player.getOpenInventory().getTopInventory().getItem(21), "missing back button");
        assertNotNull(player.getOpenInventory().getTopInventory().getItem(22), "missing home button");
    }

    private static void submitAmount(BloecoMenu menu,
                                     org.mockbukkit.mockbukkit.entity.PlayerMock player,
                                     String text) {
        var component = net.kyori.adventure.text.Component.text(text);
        menu.onAmountInput(new io.papermc.paper.event.player.AsyncChatEvent(
                true,
                player,
                java.util.Set.of(player),
                io.papermc.paper.chat.ChatRenderer.defaultRenderer(),
                component,
                component,
                net.kyori.adventure.chat.SignedMessage.system(text, component)));
    }
}
