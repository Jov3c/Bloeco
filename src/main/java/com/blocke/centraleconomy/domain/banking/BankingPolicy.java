package com.blocke.centraleconomy.domain.banking;

import com.blocke.centraleconomy.domain.money.Money;
import java.util.Objects;

public record BankingPolicy(
        int depositRateBasisPoints,
        int loanRateBasisPoints,
        int reserveRatioBasisPoints,
        Money maximumLoan,
        boolean lendingEnabled,
        int loanTermDays) {
    public BankingPolicy {
        if (depositRateBasisPoints < 0 || depositRateBasisPoints > 10_000
                || loanRateBasisPoints < 0 || loanRateBasisPoints > 10_000
                || reserveRatioBasisPoints < 0 || reserveRatioBasisPoints > 10_000) {
            throw new IllegalArgumentException("bank rates must be between 0 and 10000 basis points");
        }
        Objects.requireNonNull(maximumLoan, "maximumLoan");
        if (maximumLoan.minor() <= 0 || loanTermDays < 1) {
            throw new IllegalArgumentException("loan limits and term must be positive");
        }
    }

    public Money quotedInterest(Money principal) {
        return annualizedInterest(principal, loanRateBasisPoints, loanTermDays);
    }

    public Money quotedTotalDue(Money principal) {
        return Money.ofMinor(Math.addExact(principal.minor(), quotedInterest(principal).minor()));
    }

    public static Money annualizedInterest(Money principal, int annualBasisPoints, long termDays) {
        Objects.requireNonNull(principal, "principal");
        if (principal.minor() <= 0 || annualBasisPoints <= 0 || termDays <= 0) return Money.ofMinor(0);
        long numerator = Math.multiplyExact(Math.multiplyExact(principal.minor(), annualBasisPoints), termDays);
        return Money.ofMinor(Math.max(1L, numerator / 3_650_000L));
    }
}
