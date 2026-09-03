package com.blocke.centraleconomy.market;

import com.blocke.centraleconomy.ledger.AccountId;
import com.blocke.centraleconomy.ledger.LedgerRepository;
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

class SqliteMarketRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    private SqliteLedgerRepository ledger;
    private SqliteMarketRepository repository;
    private UUID seller;
    private UUID buyer;

    @BeforeEach
    void setUp() {
        ledger = new SqliteLedgerRepository(temporaryDirectory.resolve("economy.db"));
        repository = new SqliteMarketRepository(ledger);
        seller = UUID.randomUUID();
        buyer = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        ledger.close();
    }

    @Test
    void purchaseDecrementsListingAndStoresAnImmutableTrade() {
        MarketListing listing = repository.createListing(seller, Material.WHEAT, 12, Money.ofCents(100));

        MarketTrade trade = repository.settlePurchase(listing.id(), buyer, 5, 2);

        assertEquals(7, repository.findListing(listing.id()).orElseThrow().remainingQuantity());
        assertEquals(1, repository.tradesForListing(listing.id()).size());
        assertEquals(trade, repository.tradesForListing(listing.id()).getFirst());
        assertEquals(seller, trade.sellerId());
        assertEquals(buyer, trade.buyerId());
        assertEquals(5, trade.quantity());
        assertEquals(500, trade.totalPrice().cents());
        assertEquals(10, trade.fee().cents());
    }

    @Test
    void selfPurchaseLeavesListingAndTradeHistoryUnchanged() {
        MarketListing listing = repository.createListing(seller, Material.WHEAT, 12, Money.ofCents(100));

        assertThrows(IllegalArgumentException.class, () -> repository.settlePurchase(listing.id(), seller, 5, 2));

        assertEquals(12, repository.findListing(listing.id()).orElseThrow().remainingQuantity());
        assertEquals(List.of(), repository.tradesForListing(listing.id()));
    }

    @Test
    void emptyListingCannotBePurchasedAgain() {
        MarketListing listing = repository.createListing(seller, Material.WHEAT, 12, Money.ofCents(100));
        repository.settlePurchase(listing.id(), buyer, 12, 2);

        assertThrows(IllegalStateException.class, () -> repository.settlePurchase(listing.id(), UUID.randomUUID(), 1, 2));

        assertEquals(0, repository.findListing(listing.id()).orElseThrow().remainingQuantity());
        assertEquals(1, repository.tradesForListing(listing.id()).size());
    }

    @Test
    void failedLedgerPostingsRollBackTheListingAndTrade() {
        MarketListing listing = repository.createListing(seller, Material.WHEAT, 12, Money.ofCents(100));
        List<LedgerRepository.Posting> unaffordablePayment = List.of(new LedgerRepository.Posting(
                AccountId.player(buyer), AccountId.player(seller), Money.ofCents(500),
                TransactionType.TRANSFER, "market purchase"));

        assertThrows(IllegalStateException.class,
                () -> repository.settlePurchase(listing.id(), buyer, 5, 2, unaffordablePayment));

        assertEquals(12, repository.findListing(listing.id()).orElseThrow().remainingQuantity());
        assertEquals(List.of(), repository.tradesForListing(listing.id()));
        assertEquals(0, ledger.balance(AccountId.player(buyer)).cents());
        assertEquals(0, ledger.balance(AccountId.player(seller)).cents());
        assertEquals(0, ledger.entryCount());
    }
}
