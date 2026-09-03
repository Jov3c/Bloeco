package com.blocke.centraleconomy.market;

import com.blocke.centraleconomy.ledger.LedgerRepository;
import com.blocke.centraleconomy.money.Money;
import org.bukkit.Material;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for market listings and immutable completed trades. */
public interface MarketRepository {
    MarketListing createListing(UUID sellerId, Material material, int quantity, Money unitPrice);

    MarketTrade settlePurchase(UUID listingId, UUID buyerId, int quantity, int feeRatePercent);

    MarketTrade settlePurchase(
            UUID listingId,
            UUID buyerId,
            int quantity,
            int feeRatePercent,
            List<LedgerRepository.Posting> postings);

    MarketListing cancelListing(UUID listingId, UUID sellerId);

    Optional<MarketListing> findListing(UUID listingId);

    List<MarketListing> activeListings();

    List<MarketTrade> tradesForListing(UUID listingId);
}
