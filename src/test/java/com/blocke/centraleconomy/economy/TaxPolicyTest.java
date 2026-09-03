package com.blocke.centraleconomy.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaxPolicyTest {

    @Test
    void administratorsCanChangeOnlyWholePercentagesWithinBounds() {
        TaxPolicy policy = new TaxPolicy(5, 1, 5, 3);

        policy.setRate(TaxType.TRANSFER_INCOME, 12);

        assertEquals(12, policy.rate(TaxType.TRANSFER_INCOME));
        assertThrows(IllegalArgumentException.class, () -> policy.setRate(TaxType.MARKET_CONSUMPTION, 101));
        assertEquals(3, policy.rate(TaxType.MARKET_CONSUMPTION));
    }
}
