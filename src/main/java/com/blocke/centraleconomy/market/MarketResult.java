package com.blocke.centraleconomy.market;

import java.util.Objects;

/** Result of a listing lifecycle operation. */
public record MarketResult(MarketListing listing) {
    public MarketResult {
        Objects.requireNonNull(listing, "listing");
    }
}
