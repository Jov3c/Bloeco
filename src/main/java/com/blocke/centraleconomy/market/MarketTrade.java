package com.blocke.centraleconomy.market;

import com.blocke.centraleconomy.money.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable audit record for one completed listing purchase. */
public record MarketTrade(
        UUID id,
        UUID listingId,
        UUID sellerId,
        UUID buyerId,
        int quantity,
        Money totalPrice,
        Money fee,
        Instant createdAt) {

    public MarketTrade {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(listingId, "listingId");
        Objects.requireNonNull(sellerId, "sellerId");
        Objects.requireNonNull(buyerId, "buyerId");
        if (quantity < 1) {
            throw new IllegalArgumentException("trade quantity must be positive");
        }
        Objects.requireNonNull(totalPrice, "totalPrice");
        if (totalPrice.cents() == 0) {
            throw new IllegalArgumentException("trade total must be positive");
        }
        Objects.requireNonNull(fee, "fee");
        if (fee.cents() > totalPrice.cents()) {
            throw new IllegalArgumentException("trade fee must not exceed the total");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
