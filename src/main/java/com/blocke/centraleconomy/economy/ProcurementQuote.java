package com.blocke.centraleconomy.economy;

import com.blocke.centraleconomy.money.Money;

import java.util.Objects;
import java.util.UUID;

/** A validated, player-bound procurement amount ready for settlement. */
public record ProcurementQuote(UUID playerId, ProcurementItem item, int quantity, Money gross, Money tax) {

    public ProcurementQuote {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(item, "item");
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        Objects.requireNonNull(gross, "gross");
        Objects.requireNonNull(tax, "tax");
        if (tax.cents() > gross.cents()) {
            throw new IllegalArgumentException("tax must not exceed gross");
        }
    }
}
