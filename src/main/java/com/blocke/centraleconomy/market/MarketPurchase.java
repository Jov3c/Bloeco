package com.blocke.centraleconomy.market;

import com.blocke.centraleconomy.money.Money;

import java.util.Objects;

/** Completed market purchase with the seller's net receipt. */
public record MarketPurchase(MarketTrade trade, Money sellerNet) {
    public MarketPurchase {
        Objects.requireNonNull(trade, "trade");
        Objects.requireNonNull(sellerNet, "sellerNet");
        long expectedNet = trade.totalPrice().cents() - trade.fee().cents();
        if (sellerNet.cents() != expectedNet) {
            throw new IllegalArgumentException("seller net must equal total price minus fee");
        }
    }
}
