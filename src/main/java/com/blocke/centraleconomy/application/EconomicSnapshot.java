package com.blocke.centraleconomy.application;

import com.blocke.centraleconomy.domain.account.AccountClass;
import com.blocke.centraleconomy.domain.ledger.JournalType;

import java.util.Map;
import java.util.Objects;

/** Auditable point-in-time view used by operators and inflation monitoring. */
public record EconomicSnapshot(
        MonetaryTotals monetaryTotals,
        Map<AccountClass, Long> accountClassTotals,
        long playerCirculationMinor,
        long treasuryMinor,
        long taxRevenueMinor,
        long feeRevenueMinor,
        Map<JournalType, Long> dailyJournalVolumeMinor) {
    public EconomicSnapshot {
        Objects.requireNonNull(monetaryTotals, "monetaryTotals");
        accountClassTotals = Map.copyOf(Objects.requireNonNull(accountClassTotals, "accountClassTotals"));
        dailyJournalVolumeMinor = Map.copyOf(
                Objects.requireNonNull(dailyJournalVolumeMinor, "dailyJournalVolumeMinor"));
    }
}
