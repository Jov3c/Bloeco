package com.blocke.centraleconomy.market;

import com.blocke.centraleconomy.money.Money;

import java.util.Objects;

/** Completed market purchase with the seller receipt, buyer tax, and total buyer charge. */
public record MarketPurchase(MarketTrade trade, Money sellerNet, Money consumptionTax, Money buyerTotal) {
    public MarketPurchase {
        Objects.requireNonNull(trade, "trade");
        Objects.requireNonNull(sellerNet, "sellerNet");
        Objects.requireNonNull(consumptionTax, "consumptionTax");
        Objects.requireNonNull(buyerTotal, "buyerTotal");
        long expectedNet = trade.totalPrice().cents() - trade.fee().cents();
        if (sellerNet.cents() != expectedNet) {
            throw new IllegalArgumentException("seller net must equal total price minus fee");
        }
        if (buyerTotal.cents() != trade.totalPrice().cents() + consumptionTax.cents()) {
            throw new IllegalArgumentException("buyer total must equal price plus consumption tax");
        }
    }
}
