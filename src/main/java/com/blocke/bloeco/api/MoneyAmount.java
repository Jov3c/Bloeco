package com.blocke.bloeco.api;

/** Immutable amount in the currency's smallest unit; zero is valid for balance snapshots. */
public record MoneyAmount(long minorUnits) {
    public MoneyAmount {
        if (minorUnits < 0) throw new IllegalArgumentException("money amount cannot be negative");
    }

    public static MoneyAmount positive(long minorUnits) {
        if (minorUnits <= 0) throw new IllegalArgumentException("operation amount must be positive");
        return new MoneyAmount(minorUnits);
    }

    public boolean isPositive() {
        return minorUnits > 0;
    }
}
