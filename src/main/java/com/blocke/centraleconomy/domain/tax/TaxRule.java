package com.blocke.centraleconomy.domain.tax;

import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.money.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable, effective-dated fiscal rule retained for historical receipts. */
public record TaxRule(
        UUID versionId,
        TaxCategory category,
        int basisPoints,
        Money fixedFee,
        AccountId destinationAccountId,
        Instant effectiveFrom,
        Instant effectiveUntil,
        String actorId,
        String memo) {

    public TaxRule {
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(fixedFee, "fixedFee");
        Objects.requireNonNull(destinationAccountId, "destinationAccountId");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        if (basisPoints < 0 || basisPoints > 10_000) {
            throw new IllegalArgumentException("tax basis points must be between 0 and 10000");
        }
        if (effectiveUntil != null && effectiveUntil.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException("tax rule cannot end before it starts");
        }
        requireText(actorId, "actorId", 128);
        requireText(memo, "memo", 256);
    }

    public Money charge(Money principal) {
        return principal.percentage(basisPoints).plus(fixedFee);
    }

    private static void requireText(String value, String label, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(label + " must contain 1 through " + maximum + " characters");
        }
    }
}
