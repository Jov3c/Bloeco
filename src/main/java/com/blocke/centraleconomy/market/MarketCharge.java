package com.blocke.centraleconomy.market;

import com.blocke.centraleconomy.money.Money;

import java.util.Objects;

/** Tax-inclusive charge shown to a buyer before market settlement. */
public record MarketCharge(Money itemTotal, Money consumptionTax, Money buyerTotal) {
    public MarketCharge {
        Objects.requireNonNull(itemTotal, "itemTotal");
        Objects.requireNonNull(consumptionTax, "consumptionTax");
        Objects.requireNonNull(buyerTotal, "buyerTotal");
        if (buyerTotal.cents() != itemTotal.cents() + consumptionTax.cents()) {
            throw new IllegalArgumentException("buyer total must equal item total plus consumption tax");
        }
    }
}
