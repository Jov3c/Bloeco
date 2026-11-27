package com.blocke.centraleconomy.storage;

import com.blocke.centraleconomy.application.CentralBankService;
import com.blocke.centraleconomy.application.LedgerStore;
import com.blocke.centraleconomy.domain.account.Account;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.ledger.JournalEntry;
import com.blocke.centraleconomy.domain.ledger.JournalType;
import com.blocke.centraleconomy.domain.ledger.Posting;
import com.blocke.centraleconomy.domain.money.Money;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class JournalPageAssertions {
    private JournalPageAssertions() {}

    public static void assertStableJournalPages(LedgerStore store) {
        var bank = new CentralBankService(store, Clock.systemUTC());
        bank.initializeCentralAccounts();
        bank.bootstrapTreasury(Money.parse("1000000"));
        Account player = Account.player(UUID.randomUUID());
        store.createAccount(player);
        Optional<AccountId> account = Optional.of(player.id());
        Instant timestamp = Instant.now();
        for (int i = 0; i < 23; i++) allocate(store, player.id(), timestamp);

        List<JournalEntry> fullHistory = store.recentEntries(account, 100);
        assertEquals(23, fullHistory.size());
        List<JournalEntry> first = store.journalPage(account, 21, null);
        assertEquals(fullHistory.subList(0, 21), first);

        allocate(store, player.id(), timestamp.plusSeconds(1));
        assertEquals(fullHistory.subList(21, 23), store.journalPage(account, 21, first.getLast().id()));
    }

    private static void allocate(LedgerStore store, AccountId player, Instant timestamp) {
        store.commit(JournalEntry.create(UUID.randomUUID(), JournalType.TREASURY_ALLOCATION,
                "账单翻页测试", null, null, timestamp,
                List.of(new Posting(AccountId.treasury(), -1), new Posting(player, 1))));
    }
}
