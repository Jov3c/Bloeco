package com.blocke.centraleconomy.economy;

import com.blocke.centraleconomy.money.Money;

import java.util.Objects;

/** Amounts actually transferred by a settled procurement. */
public record ProcurementResult(Money gross, Money tax, Money playerNet) {

    public ProcurementResult {
        Objects.requireNonNull(gross, "gross");
        Objects.requireNonNull(tax, "tax");
        Objects.requireNonNull(playerNet, "playerNet");
        if (tax.cents() > gross.cents() || gross.cents() - tax.cents() != playerNet.cents()) {
            throw new IllegalArgumentException("playerNet must equal gross minus tax");
        }
    }
}
