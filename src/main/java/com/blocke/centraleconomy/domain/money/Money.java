package com.blocke.centraleconomy.domain.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/** A non-negative monetary amount stored exclusively in minor units. */
public record Money(long minor) {
    private static final Pattern DECIMAL = Pattern.compile("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,2})?");

    public Money {
        if (minor < 0) {
            throw new IllegalArgumentException("money must not be negative");
        }
    }

    public static Money ofMinor(long minor) {
        return new Money(minor);
    }

    public static Money parse(String decimal) {
        String value = Objects.requireNonNull(decimal, "decimal").trim();
        if (!DECIMAL.matcher(value).matches()) {
            throw new IllegalArgumentException("amount must be a non-negative decimal with at most two places");
        }
        try {
            return ofMinor(new BigDecimal(value).movePointRight(2).longValueExact());
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("amount exceeds the supported range", exception);
        }
    }

    public Money plus(Money other) {
        Objects.requireNonNull(other, "other");
        try {
            return ofMinor(Math.addExact(minor, other.minor));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("amount exceeds the supported range", exception);
        }
    }

    public Money minus(Money other) {
        Objects.requireNonNull(other, "other");
        try {
            return ofMinor(Math.subtractExact(minor, other.minor));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("resulting amount would be negative", exception);
        }
    }

    public Money percentage(int basisPoints) {
        if (basisPoints < 0 || basisPoints > 10_000) {
            throw new IllegalArgumentException("basis points must be between 0 and 10000");
        }
        long units = minor / 10_000;
        long remainder = minor % 10_000;
        try {
            long whole = Math.multiplyExact(units, basisPoints);
            long roundedRemainder = BigDecimal.valueOf(remainder)
                    .multiply(BigDecimal.valueOf(basisPoints))
                    .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP)
                    .longValueExact();
            return ofMinor(Math.addExact(whole, roundedRemainder));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("percentage exceeds the supported range", exception);
        }
    }
}
