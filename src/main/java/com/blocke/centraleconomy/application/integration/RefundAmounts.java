package com.blocke.centraleconomy.application.integration;

import com.blocke.centraleconomy.domain.ledger.LedgerException;
import com.blocke.centraleconomy.domain.money.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class RefundAmounts {
    private RefundAmounts() {
    }

    public static long taxFor(SettlementRecord charge, Money principal, long refundedPrincipal, long refundedTax) {
        long principalLeft = Math.subtractExact(charge.principal().minor(), refundedPrincipal);
        if (principal.minor() > principalLeft) {
            throw new LedgerException(LedgerException.Code.INVALID_AMOUNT, "refund exceeds remaining principal");
        }
        long taxLeft = Math.subtractExact(charge.tax().minor(), refundedTax);
        if (principal.minor() == principalLeft) return taxLeft;

        long proratedTax = BigDecimal.valueOf(charge.tax().minor())
                .multiply(BigDecimal.valueOf(principal.minor()))
                .divide(BigDecimal.valueOf(charge.principal().minor()), 0, RoundingMode.HALF_UP)
                .longValueExact();
        return Math.min(taxLeft, proratedTax);
    }
}
