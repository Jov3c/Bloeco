package com.blocke.centraleconomy.money;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {

    @Test
    void rejectsNegativeAndNonFiniteVaultAmounts() {
        assertThrows(IllegalArgumentException.class, () -> Money.ofCents(-1));
        assertThrows(IllegalArgumentException.class, () -> Money.fromVault(Double.NaN));
        assertEquals(1234L, Money.fromVault(12.34).cents());
    }
}
