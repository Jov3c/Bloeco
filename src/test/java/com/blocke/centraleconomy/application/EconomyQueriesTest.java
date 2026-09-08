package com.blocke.centraleconomy.application;

import com.blocke.centraleconomy.application.command.PlayerPayment;
import com.blocke.centraleconomy.domain.account.AccountClass;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.ledger.JournalType;
import com.blocke.centraleconomy.domain.money.Money;
import com.blocke.centraleconomy.storage.sqlite.SqliteLedgerStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyQueriesTest {
    private static final UUID SENDER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID RECIPIENT = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC);

    @TempDir Path temporaryDirectory;
    private SqliteLedgerStore store;
    private CentralBankService bank;
    private PlayerPaymentService payments;
    private EconomyQueries queries;

    @BeforeEach
    void setUp() {
        store = new SqliteLedgerStore(temporaryDirectory.resolve("economy.db"));
        bank = new CentralBankService(store, CLOCK);
        bank.initializeCentralAccounts();
        TaxRuleService taxes = new TaxRuleService(store, CLOCK);
        taxes.initializeDefaults();
        payments = new PlayerPaymentService(store, taxes, CLOCK);
        queries = new EconomyQueries(store, CLOCK);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void transfersAndTaxesDoNotChangeSupplyAndAreVisibleByAccountClass() {
        issueAndFundSender(100_000);
        long before = queries.snapshot().monetaryTotals().netSupplyMinor();

        payments.pay(new PlayerPayment(SENDER, RECIPIENT, Money.ofMinor(10_000), "gift", "query-pay"));
        EconomicSnapshot snapshot = queries.snapshot();

        assertEquals(before, snapshot.monetaryTotals().netSupplyMinor());
        assertEquals(-100_000L, snapshot.accountClassTotals().get(AccountClass.MONETARY_AUTHORITY));
        assertEquals(600L, snapshot.accountClassTotals().get(AccountClass.FISCAL));
        assertEquals(99_400L, snapshot.playerCirculationMinor());
        assertEquals(500L, snapshot.taxRevenueMinor());
        assertEquals(100L, snapshot.feeRevenueMinor());
        assertEquals(10_100L, snapshot.dailyJournalVolumeMinor().get(JournalType.PLAYER_TRANSFER));
    }

    @Test
    void recentAccountJournalIsNewestFirst() {
        issueAndFundSender(20_000);
        payments.pay(new PlayerPayment(SENDER, RECIPIENT, Money.ofMinor(1_000), "gift", "recent-pay"));

        var entries = queries.recentJournal(AccountId.player(SENDER), 10);

        assertEquals(2, entries.size());
        assertEquals(JournalType.PLAYER_TRANSFER, entries.getFirst().type());
        assertTrue(queries.verifyIntegrity().valid());
    }

    private void issueAndFundSender(long amount) {
        UUID request = bank.requestIssuance(Money.ofMinor(amount), "admin:requester", "query test");
        bank.approveIssuance(request, "admin:approver");
        bank.executeIssuance(request, "admin:requester", "query-issue-" + amount);
        bank.adjustPlayerBalance(SENDER, Money.ofMinor(amount), "admin:treasurer", "fund sender",
                "query-fund-" + amount);
    }
}
