package com.blocke.centraleconomy.application;

import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.ledger.JournalEntry;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Read-only reporting service derived entirely from the authoritative journal. */
public final class EconomyQueries {
    private final LedgerStore store;
    private final Clock clock;

    public EconomyQueries(LedgerStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public EconomicSnapshot snapshot() {
        var dayStart = clock.instant().atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        return new EconomicSnapshot(
                store.monetaryTotals(),
                store.accountClassTotals(),
                store.playerCirculation(),
                store.balance(AccountId.treasury()),
                store.balance(AccountId.taxRevenue()),
                store.balance(AccountId.feeRevenue()),
                store.journalVolumeSince(dayStart));
    }

    public List<JournalEntry> recentJournal(AccountId accountId, int limit) {
        return store.recentEntries(Optional.of(Objects.requireNonNull(accountId, "accountId")), checkedLimit(limit));
    }

    public List<JournalEntry> recentJournal(int limit) {
        return store.recentEntries(Optional.empty(), checkedLimit(limit));
    }

    public IntegrityReport verifyIntegrity() {
        return store.verifyIntegrity();
    }

    private static int checkedLimit(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("journal limit must be between 1 and 100");
        return limit;
    }
}
