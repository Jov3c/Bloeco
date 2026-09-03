package com.blocke.centraleconomy.paper;

import com.blocke.centraleconomy.ledger.SqliteLedgerRepository;
import com.blocke.centraleconomy.ledger.AccountId;
import com.blocke.centraleconomy.ledger.TransactionType;
import com.blocke.centraleconomy.market.MarketListing;
import com.blocke.centraleconomy.market.MarketService;
import com.blocke.centraleconomy.market.SqliteMarketRepository;
import com.blocke.centraleconomy.money.Money;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketMenuTest {

    @TempDir
    Path temporaryDirectory;

    private ServerMock server;
    private Plugin plugin;
    private SqliteLedgerRepository ledger;
    private MarketService market;
    private MarketMenu menu;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        ledger = new SqliteLedgerRepository(temporaryDirectory.resolve("economy.db"));
        market = new MarketService(ledger, new SqliteMarketRepository(ledger), 2);
        menu = new MarketMenu(plugin, market);
    }

    @AfterEach
    void tearDown() {
        if (ledger != null) {
            ledger.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void opensCustomHolderAndEscrowsOnlyRequestedPlainMaterial() {
        PlayerMock player = server.addPlayer();
        player.getInventory().addItem(new ItemStack(Material.WHEAT, 12));
        ItemStack namedWheat = new ItemStack(Material.WHEAT, 4);
        var meta = namedWheat.getItemMeta();
        meta.setDisplayName("Named wheat");
        namedWheat.setItemMeta(meta);
        player.getInventory().addItem(namedWheat);

        menu.open(player);
        assertInstanceOf(MarketMenu.MarketHolder.class, player.getOpenInventory().getTopInventory().getHolder());

        MarketListing listing = menu.createListing(player, Material.WHEAT, 10, Money.ofCents(125));

        assertEquals(10, listing.remainingQuantity());
        assertEquals(2, plainCount(player, Material.WHEAT));
        assertEquals(4, namedCount(player, Material.WHEAT));
    }

    @Test
    void confirmedPurchaseSettlesThenDeliversOnePlainMaterial() {
        PlayerMock seller = server.addPlayer();
        PlayerMock buyer = server.addPlayer();
        MarketListing listing = market.createListing(seller.getUniqueId(), Material.WHEAT, 2, Money.ofCents(100));
        ledger.credit(AccountId.player(buyer.getUniqueId()), Money.ofCents(103), TransactionType.ISSUE, "buyer funds");

        menu.open(buyer);
        InventoryClickEvent chooseListing = click(buyer, 0);
        menu.onInventoryClick(chooseListing);
        assertTrue(chooseListing.isCancelled());
        assertInstanceOf(MarketMenu.MarketHolder.class, buyer.getOpenInventory().getTopInventory().getHolder());

        InventoryClickEvent confirmPurchase = click(buyer, 11);
        menu.onInventoryClick(confirmPurchase);

        assertTrue(confirmPurchase.isCancelled());
        assertEquals(1, plainCount(buyer, Material.WHEAT));
        assertEquals(0, ledger.balance(AccountId.player(buyer.getUniqueId())).cents());
        assertEquals(1, market.browse().getFirst().remainingQuantity());
    }

    @Test
    void fullInventoryRejectsPurchaseBeforeSettlement() {
        PlayerMock seller = server.addPlayer();
        PlayerMock buyer = server.addPlayer();
        MarketListing listing = market.createListing(seller.getUniqueId(), Material.WHEAT, 2, Money.ofCents(100));
        ledger.credit(AccountId.player(buyer.getUniqueId()), Money.ofCents(100), TransactionType.ISSUE, "buyer funds");
        ItemStack[] fullInventory = buyer.getInventory().getStorageContents();
        for (int index = 0; index < fullInventory.length; index++) {
            fullInventory[index] = new ItemStack(Material.COBBLESTONE, Material.COBBLESTONE.getMaxStackSize());
        }
        buyer.getInventory().setStorageContents(fullInventory);

        menu.open(buyer);
        menu.onInventoryClick(click(buyer, 0));
        menu.onInventoryClick(click(buyer, 11));

        assertEquals(100, ledger.balance(AccountId.player(buyer.getUniqueId())).cents());
        assertEquals(2, market.browse().getFirst().remainingQuantity());
        assertTrue(buyer.nextMessage().contains("inventory space"));
    }

    private static InventoryClickEvent click(PlayerMock player, int rawSlot) {
        return new InventoryClickEvent(
                player.getOpenInventory(), InventoryType.SlotType.CONTAINER, rawSlot,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
    }

    private static int plainCount(PlayerMock player, Material material) {
        int count = 0;
        ItemStack plain = new ItemStack(material);
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.isSimilar(plain)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private static int namedCount(PlayerMock player, Material material) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == material && !stack.isSimilar(new ItemStack(material))) {
                count += stack.getAmount();
            }
        }
        return count;
    }
}
