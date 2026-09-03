package com.blocke.centraleconomy.economy;

import com.blocke.centraleconomy.money.Money;

import java.util.Objects;

/** Immutable configuration for one item the treasury may procure. */
public record ProcurementItem(String materialKey, Money unitPrice, int maxPerSale, boolean enabled) {

    public ProcurementItem {
        if (materialKey == null || materialKey.isBlank()) {
            throw new IllegalArgumentException("materialKey must not be blank");
        }
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (unitPrice.cents() == 0) {
            throw new IllegalArgumentException("unitPrice must be positive");
        }
        if (maxPerSale < 1) {
            throw new IllegalArgumentException("maxPerSale must be positive");
        }
    }
}
