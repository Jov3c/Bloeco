package com.blocke.centraleconomy.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** A non-negative monetary amount stored as integer cents. */
public record Money(long cents) {

    public Money {
        if (cents < 0) {
            throw new IllegalArgumentException("cents must be non-negative");
        }
    }

    public static Money ofCents(long cents) {
        return new Money(cents);
    }

    public static Money fromVault(double amount) {
        if (!Double.isFinite(amount) || amount < 0) {
            throw new IllegalArgumentException("amount must be finite and non-negative");
        }

        try {
            long cents = BigDecimal.valueOf(amount)
                    .movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
            return ofCents(cents);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("amount exceeds supported range", exception);
        }
    }
}
