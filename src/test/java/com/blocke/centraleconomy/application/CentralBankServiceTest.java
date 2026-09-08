package com.blocke.centraleconomy.application;

import com.blocke.centraleconomy.domain.account.Account;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.ledger.LedgerException;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CentralBankServiceTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final String REQUESTER = "admin:requester";
    private static final String APPROVER = "admin:approver";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC);

    @TempDir Path temporaryDirectory;
    private SqliteLedgerStore store;
    private CentralBankService bank;

    @BeforeEach
    void setUp() {
        store = new SqliteLedgerStore(temporaryDirectory.resolve("economy.db"));
        bank = new CentralBankService(store, CLOCK);
        bank.initializeCentralAccounts();
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void playerGrantFailsWhenTreasuryIsEmptyAndCannotMint() {
        assertThrows(LedgerException.class, () -> bank.adjustPlayerBalance(
                PLAYER, Money.ofMinor(1_000), REQUESTER, "starter funds", "grant-1"));

        assertEquals(0L, store.monetaryTotals().netSupplyMinor());
        assertEquals(0, store.entryCount());
    }

    @Test
    void issuanceRequiresApprovalAndCreditsOnlyTreasury() {
        UUID request = bank.requestIssuance(Money.ofMinor(10_000), REQUESTER, "server launch");
        assertThrows(LedgerException.class,
                () -> bank.executeIssuance(request, REQUESTER, "issue-before-approval"));

        bank.approveIssuance(request, APPROVER);
        var receipt = bank.executeIssuance(request, REQUESTER, "issue-1");

        assertNotNull(receipt.entryId());
        assertEquals(10_000L, store.balance(AccountId.treasury()));
        assertEquals(-10_000L, store.balance(AccountId.issuanceControl()));
        assertEquals(10_000L, store.monetaryTotals().netSupplyMinor());
    }

    @Test
    void issuanceReplayReturnsTheOriginalJournalWithoutMintingAgain() {
        UUID request = bank.requestIssuance(Money.ofMinor(10_000), REQUESTER, "server launch");
        bank.approveIssuance(request, APPROVER);
        var first = bank.executeIssuance(request, REQUESTER, "issue-replay");
        var replay = bank.executeIssuance(request, REQUESTER, "issue-replay");

        assertEquals(first.entryId(), replay.entryId());
        assertEquals(10_000L, store.monetaryTotals().netSupplyMinor());
        assertEquals(1, store.entryCount());
    }

    @Test
    void requesterCannotApproveOwnIssuance() {
        UUID request = bank.requestIssuance(Money.ofMinor(10_000), REQUESTER, "server launch");
        assertThrows(LedgerException.class, () -> bank.approveIssuance(request, REQUESTER));
    }

    @Test
    void issuanceAboveDailyLimitIsRejectedWithoutJournal() {
        bank.setPolicyLimit(CentralBankService.DAILY_ISSUANCE_LIMIT, 5_000, APPROVER, "conservative limit");
        UUID request = bank.requestIssuance(Money.ofMinor(5_001), REQUESTER, "too much");
        bank.approveIssuance(request, APPROVER);

        assertThrows(LedgerException.class, () -> bank.executeIssuance(request, REQUESTER, "issue-limit"));
        assertEquals(0L, store.monetaryTotals().issuedMinor());
        assertEquals(0, store.entryCount());
    }

    @Test
    void retirementReducesSupplyAndCannotBeSpentAgain() {
        issue(10_000);
        bank.retireFromTreasury(Money.ofMinor(2_000), REQUESTER, "currency sink", "retire-1");

        assertEquals(8_000L, store.monetaryTotals().netSupplyMinor());
        assertEquals(8_000L, store.balance(AccountId.treasury()));
        assertEquals(2_000L, store.balance(AccountId.retiredControl()));
    }

    @Test
    void loweringAPlayerBalanceReturnsFundsToTreasury() {
        issue(10_000);
        bank.adjustPlayerBalance(PLAYER, Money.ofMinor(4_000), REQUESTER, "starter", "grant-player");
        bank.adjustPlayerBalance(PLAYER, Money.ofMinor(1_500), REQUESTER, "reclaim", "reclaim-player");

        assertEquals(1_500L, store.balance(AccountId.player(PLAYER)));
        assertEquals(8_500L, store.balance(AccountId.treasury()));
        assertEquals(10_000L, store.monetaryTotals().netSupplyMinor());
    }

    @Test
    void reversalRestoresBalancesButCannotBeAppliedTwice() {
        issue(10_000);
        var allocation = bank.adjustPlayerBalance(
                PLAYER, Money.ofMinor(4_000), REQUESTER, "starter", "grant-reversal");
        bank.reverse(allocation.entryId(), APPROVER, "cancel mistaken grant", "reverse-1");

        assertEquals(0L, store.balance(AccountId.player(PLAYER)));
        assertEquals(10_000L, store.balance(AccountId.treasury()));
        assertEquals(10_000L, store.monetaryTotals().netSupplyMinor());
        LedgerException repeated = assertThrows(LedgerException.class,
                () -> bank.reverse(allocation.entryId(), APPROVER, "repeat", "reverse-2"));
        assertEquals(LedgerException.Code.INVALID_JOURNAL, repeated.code());
    }

    @Test
    void bootstrapCreatesExactlyOneMillionInTreasuryAndCannotReplay() {
        Optional<JournalReceipt> first = bank.bootstrapTreasury(Money.parse("1000000.00"));
        Optional<JournalReceipt> replay = bank.bootstrapTreasury(Money.parse("2000000.00"));

        assertEquals(first, replay);
        assertEquals(100_000_000L, store.balance(AccountId.treasury()));
        assertEquals(100_000_000L, store.monetaryTotals().netSupplyMinor());
        assertEquals(1, store.entryCount());
    }

    @Test
    void bootstrapNeverAddsGenesisMoneyToAnExistingLedger() {
        issue(25_000);

        Optional<JournalReceipt> result = bank.bootstrapTreasury(Money.parse("1000000.00"));

        assertTrue(result.isEmpty());
        assertEquals(25_000L, store.monetaryTotals().netSupplyMinor());
    }

    @Test
    void starterFundsAreAllocatedOnceEvenIfPlayerLaterReturnsToZero() {
        bank.bootstrapTreasury(Money.parse("1000000.00"));
        JournalReceipt first = bank.grantStarterFunds(PLAYER, Money.parse("100.00"));
        bank.adjustPlayerBalance(PLAYER, Money.ofMinor(0), REQUESTER, "spent starter funds", "starter-spent");
        int entriesBeforeReplay = store.entryCount();
        JournalReceipt replay = bank.grantStarterFunds(PLAYER, Money.parse("100.00"));

        assertEquals(first, replay);
        assertEquals(0L, store.balance(AccountId.player(PLAYER)));
        assertEquals(entriesBeforeReplay, store.entryCount());
        assertEquals(100_000_000L, store.monetaryTotals().netSupplyMinor());
    }

    private void issue(long amount) {
        UUID request = bank.requestIssuance(Money.ofMinor(amount), REQUESTER, "test issue");
        bank.approveIssuance(request, APPROVER);
        bank.executeIssuance(request, REQUESTER, "issue-" + amount);
    }
}
