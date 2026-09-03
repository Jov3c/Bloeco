package com.blocke.centraleconomy.paper;

import com.blocke.centraleconomy.economy.EconomyService;
import com.blocke.centraleconomy.economy.ProcurementItem;
import com.blocke.centraleconomy.ledger.AccountId;
import com.blocke.centraleconomy.ledger.SqliteLedgerRepository;
import com.blocke.centraleconomy.ledger.TransactionType;
import com.blocke.centraleconomy.money.Money;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcurementMenuTest {

    @TempDir
    Path temporaryDirectory;

    private ServerMock server;
    private Plugin plugin;
    private SqliteLedgerRepository repository;
    private EconomyService service;
    private ProcurementMenu menu;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        repository = new SqliteLedgerRepository(temporaryDirectory.resolve("economy.db"));
        service = new EconomyService(repository, 5);
        menu = new ProcurementMenu(plugin, service, List.of(
                new ProcurementItem("minecraft:wheat", Money.ofCents(100), 10, true)));
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void clickingConfiguredMaterialPaysAndRemovesOnlyCappedPlainItems() {
        PlayerMock player = server.addPlayer();
        player.getInventory().addItem(new ItemStack(Material.WHEAT, 12));
        service.issueToTreasury(Money.ofCents(2_000), "seed");

        menu.clickConfiguredItem(player, Material.WHEAT);

        assertEquals(2, plainCount(player, Material.WHEAT));
        assertEquals(950, service.playerBalance(player.getUniqueId()).cents());
    }

    @Test
    void clickingConfiguredMaterialDoesNotAcceptCustomItemVariants() {
        PlayerMock player = server.addPlayer();
        ItemStack namedWheat = new ItemStack(Material.WHEAT, 4);
        var meta = namedWheat.getItemMeta();
        meta.setDisplayName("Not plain wheat");
        namedWheat.setItemMeta(meta);
        player.getInventory().addItem(namedWheat);
        service.issueToTreasury(Money.ofCents(2_000), "seed");

        menu.clickConfiguredItem(player, Material.WHEAT);

        assertEquals(4, player.getInventory().all(Material.WHEAT).values().stream()
                .mapToInt(ItemStack::getAmount).sum());
        assertEquals(0, service.playerBalance(player.getUniqueId()).cents());
        assertTrue(player.nextMessage().contains("plain"));
    }

    @Test
    void rejectedSettlementRestoresRemovedPlainItemsImmediately() {
        PlayerMock player = server.addPlayer();
        player.getInventory().addItem(new ItemStack(Material.WHEAT, 12));

        menu.clickConfiguredItem(player, Material.WHEAT);

        assertEquals(12, plainCount(player, Material.WHEAT));
        assertEquals(0, service.playerBalance(player.getUniqueId()).cents());
        assertTrue(player.nextMessage().contains("rejected"));
    }

    @Test
    void treasuryAdministratorCanAllocateExistingFundsToTheDefaultBlockStockReserve() {
        PlayerMock administrator = server.addPlayer();
        administrator.setOp(true);
        EconomyCommand command = new EconomyCommand(service, menu);
        service.issueToTreasury(Money.ofCents(10_000), "approved supply");

        assertTrue(command.onCommand(administrator, null, "economy",
                new String[]{"reserve", "fund", "50.00", "bluechip", "liquidity"}));

        UUID reserveId = UUID.fromString("00000000-0000-0000-0000-000000000098");
        assertEquals(5_000, service.treasuryBalance().cents());
        assertEquals(5_000, service.playerBalance(reserveId).cents());
    }

    @Test
    void payCommandSettlesAnOnlinePlayerPaymentWithFeeAndIncomeTax() {
        PlayerMock payer = server.addPlayer();
        PlayerMock recipient = server.addPlayer();
        repository.credit(AccountId.player(payer.getUniqueId()), Money.ofCents(10_100), TransactionType.ISSUE, "payer funds");
        PayCommand command = new PayCommand(service);

        assertTrue(command.onCommand(payer, null, "pay", new String[]{recipient.getName(), "100.00"}));

        assertEquals(0, service.playerBalance(payer.getUniqueId()).cents());
        assertEquals(9_500, service.playerBalance(recipient.getUniqueId()).cents());
        assertEquals(600, service.treasuryBalance().cents());
    }

    private static int plainCount(PlayerMock player, Material material) {
        int quantity = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.isSimilar(new ItemStack(material))) {
                quantity += stack.getAmount();
            }
        }
        return quantity;
    }
}
