package com.blocke.centraleconomy.market;

import com.blocke.centraleconomy.ledger.AccountId;
import com.blocke.centraleconomy.ledger.SqliteLedgerRepository;
import com.blocke.centraleconomy.ledger.TransactionType;
import com.blocke.centraleconomy.money.Money;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarketServiceTest {

    @TempDir
    Path temporaryDirectory;

    private SqliteLedgerRepository ledger;
    private SqliteMarketRepository marketRepository;
    private MarketService service;
    private UUID seller;
    private UUID buyer;

    @BeforeEach
    void setUp() {
        ledger = new SqliteLedgerRepository(temporaryDirectory.resolve("economy.db"));
        marketRepository = new SqliteMarketRepository(ledger);
        service = new MarketService(ledger, marketRepository, 2);
        seller = UUID.randomUUID();
        buyer = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        ledger.close();
    }

    @Test
    void purchaseSettlesBuyerSellerAndTreasuryInOneConservedBatch() {
        ledger.credit(AccountId.player(buyer), Money.ofCents(10_000), TransactionType.ISSUE, "buyer funds");
        MarketListing listing = service.createListing(seller, Material.WHEAT, 10, Money.ofCents(100));

        MarketPurchase purchase = service.buy(buyer, listing.id(), 5);

        assertEquals(500, purchase.trade().totalPrice().cents());
        assertEquals(10, purchase.trade().fee().cents());
        assertEquals(490, purchase.sellerNet().cents());
        assertEquals(9_500, ledger.balance(AccountId.player(buyer)).cents());
        assertEquals(490, ledger.balance(AccountId.player(seller)).cents());
        assertEquals(10, ledger.balance(AccountId.treasury()).cents());
        assertEquals(5, marketRepository.findListing(listing.id()).orElseThrow().remainingQuantity());
        assertEquals(3, ledger.entryCount());
    }

    @Test
    void insufficientBuyerFundsLeaveMoneyAndListingUnchanged() {
        ledger.credit(AccountId.player(buyer), Money.ofCents(499), TransactionType.ISSUE, "limited funds");
        MarketListing listing = service.createListing(seller, Material.WHEAT, 10, Money.ofCents(100));

        assertThrows(IllegalStateException.class, () -> service.buy(buyer, listing.id(), 5));

        assertEquals(499, ledger.balance(AccountId.player(buyer)).cents());
        assertEquals(0, ledger.balance(AccountId.player(seller)).cents());
        assertEquals(0, ledger.balance(AccountId.treasury()).cents());
        assertEquals(10, marketRepository.findListing(listing.id()).orElseThrow().remainingQuantity());
        assertEquals(List.of(), marketRepository.tradesForListing(listing.id()));
        assertEquals(1, ledger.entryCount());
    }

    @Test
    void selfPurchaseLeavesMoneyAndListingUnchanged() {
        ledger.credit(AccountId.player(seller), Money.ofCents(10_000), TransactionType.ISSUE, "seller funds");
        MarketListing listing = service.createListing(seller, Material.WHEAT, 10, Money.ofCents(100));

        assertThrows(IllegalArgumentException.class, () -> service.buy(seller, listing.id(), 5));

        assertEquals(10_000, ledger.balance(AccountId.player(seller)).cents());
        assertEquals(0, ledger.balance(AccountId.treasury()).cents());
        assertEquals(10, marketRepository.findListing(listing.id()).orElseThrow().remainingQuantity());
        assertEquals(List.of(), marketRepository.tradesForListing(listing.id()));
        assertEquals(1, ledger.entryCount());
    }

    @Test
    void zeroFeePurchaseOmitsTheFeePostingInsteadOfCreatingAZeroAmountEntry() {
        MarketService zeroFeeService = new MarketService(ledger, marketRepository, 0);
        ledger.credit(AccountId.player(buyer), Money.ofCents(500), TransactionType.ISSUE, "buyer funds");
        MarketListing listing = zeroFeeService.createListing(seller, Material.WHEAT, 5, Money.ofCents(100));

        zeroFeeService.buy(buyer, listing.id(), 5);

        assertEquals(500, ledger.balance(AccountId.player(seller)).cents());
        assertEquals(0, ledger.balance(AccountId.treasury()).cents());
        assertEquals(2, ledger.entryCount());
    }

    @Test
    void cancelReturnsTheSellerRemainingListingAndRemovesItFromBrowseResults() {
        MarketListing listing = service.createListing(seller, Material.WHEAT, 10, Money.ofCents(100));

        MarketResult result = service.cancel(seller, listing.id());

        assertEquals(MarketListing.Status.CANCELLED, result.listing().status());
        assertEquals(10, result.listing().remainingQuantity());
        assertEquals(List.of(), service.browse());
        assertEquals(MarketListing.Status.CANCELLED,
                marketRepository.findListing(listing.id()).orElseThrow().status());
    }

    @Test
    void browseReturnsOnlyActiveListings() {
        MarketListing active = service.createListing(seller, Material.WHEAT, 10, Money.ofCents(100));
        MarketListing cancelled = service.createListing(UUID.randomUUID(), Material.CARROT, 4, Money.ofCents(50));
        service.cancel(cancelled.sellerId(), cancelled.id());

        assertEquals(List.of(active), service.browse());
    }

    @Test
    void rejectsInvalidMarketTermsBeforeCreatingOrSettlingListings() {
        assertThrows(IllegalArgumentException.class,
                () -> new MarketService(ledger, marketRepository, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new MarketService(ledger, marketRepository, 21));
        assertThrows(IllegalArgumentException.class,
                () -> service.createListing(seller, Material.WHEAT, 0, Money.ofCents(100)));
        assertThrows(IllegalArgumentException.class,
                () -> service.createListing(seller, Material.WHEAT, 1, Money.ofCents(0)));
        assertEquals(List.of(), service.browse());
    }

    @Test
    void rejectsPurchaseTotalsThatOverflowCents() {
        MarketListing listing = service.createListing(seller, Material.WHEAT, 2, Money.ofCents(Long.MAX_VALUE));

        assertThrows(IllegalArgumentException.class, () -> service.buy(buyer, listing.id(), 2));

        assertEquals(2, marketRepository.findListing(listing.id()).orElseThrow().remainingQuantity());
    }
}
