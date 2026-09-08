package com.blocke.centraleconomy.application;

import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.money.Money;
import com.blocke.centraleconomy.domain.tax.TaxCategory;
import com.blocke.centraleconomy.domain.tax.TaxRule;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Owns all tax and fee policy; callers receive the exact version used for settlement. */
public final class TaxRuleService {
    private final LedgerStore store;
    private final Clock clock;

    public TaxRuleService(LedgerStore store) {
        this(store, Clock.systemUTC());
    }

    public TaxRuleService(LedgerStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void initializeDefaults() {
        if (store.currentTaxRule(TaxCategory.PLAYER_TRANSFER_FEE, clock.instant()).isEmpty()) {
            change(TaxCategory.PLAYER_TRANSFER_FEE, 100, 0, "system:bootstrap", "default transfer fee");
        }
        if (store.currentTaxRule(TaxCategory.PLAYER_TRANSFER_INCOME, clock.instant()).isEmpty()) {
            change(TaxCategory.PLAYER_TRANSFER_INCOME, 500, 0, "system:bootstrap", "default income tax");
        }
    }

    public TaxRule change(
            TaxCategory category, int basisPoints, long fixedMinor, String actorId, String memo) {
        Objects.requireNonNull(category, "category");
        if (basisPoints < 0 || basisPoints > 10_000) {
            throw new IllegalArgumentException("tax basis points must be between 0 and 10000");
        }
        if (fixedMinor < 0) throw new IllegalArgumentException("fixed fee must not be negative");
        AccountId destination = switch (category) {
            case PLAYER_TRANSFER_FEE -> AccountId.feeRevenue();
            case PLAYER_TRANSFER_INCOME -> AccountId.taxRevenue();
        };
        return store.changeTaxRule(new TaxRule(UUID.randomUUID(), category, basisPoints,
                Money.ofMinor(fixedMinor), destination, clock.instant(), null, actorId, memo));
    }

    public TaxRule current(TaxCategory category) {
        return store.currentTaxRule(Objects.requireNonNull(category, "category"), clock.instant())
                .orElseThrow(() -> new IllegalStateException("tax rule is not initialized: " + category));
    }

    public TaxRule byVersion(UUID versionId) {
        return store.taxRule(Objects.requireNonNull(versionId, "versionId"))
                .orElseThrow(() -> new IllegalArgumentException("tax rule version does not exist"));
    }
}
