package com.blocke.centraleconomy.application;

/** Currency supply derived from monetary authority control accounts. */
public record MonetaryTotals(long issuedMinor, long retiredMinor, long netSupplyMinor) {
    public MonetaryTotals {
        if (issuedMinor < 0 || retiredMinor < 0 || netSupplyMinor < 0
                || netSupplyMinor != Math.subtractExact(issuedMinor, retiredMinor)) {
            throw new IllegalArgumentException("invalid monetary totals");
        }
    }
}
