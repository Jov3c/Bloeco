package com.blocke.centraleconomy.domain.money;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {
    @Test
    void parsesAtMostTwoDecimalPlacesWithoutFloatingPoint() {
        assertEquals(12_345L, Money.parse("123.45").minor());
        assertEquals(12_300L, Money.parse("123").minor());
        assertEquals(12_340L, Money.parse("123.4").minor());
        assertThrows(IllegalArgumentException.class, () -> Money.parse("1.001"));
        assertThrows(IllegalArgumentException.class, () -> Money.parse("NaN"));
    }

    @Test
    void roundsBasisPointsHalfUp() {
        assertEquals(1L, Money.ofMinor(50).percentage(100).minor());
        assertEquals(5L, Money.ofMinor(999).percentage(50).minor());
        assertEquals(500L, Money.ofMinor(10_000).percentage(500).minor());
    }

    @Test
    void checkedArithmeticNeverCreatesNegativeMoney() {
        assertEquals(Money.ofMinor(300), Money.ofMinor(100).plus(Money.ofMinor(200)));
        assertEquals(Money.ofMinor(100), Money.ofMinor(300).minus(Money.ofMinor(200)));
        assertThrows(IllegalArgumentException.class, () -> Money.ofMinor(100).minus(Money.ofMinor(101)));
        assertThrows(IllegalArgumentException.class, () -> Money.ofMinor(Long.MAX_VALUE).plus(Money.ofMinor(1)));
    }
}
