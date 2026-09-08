package com.blocke.centraleconomy.storage.sqlite;

import com.blocke.centraleconomy.application.IntegrityReport;
import com.blocke.centraleconomy.domain.account.Account;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.ledger.JournalEntry;
import com.blocke.centraleconomy.domain.ledger.JournalType;
import com.blocke.centraleconomy.domain.ledger.LedgerException;
import com.blocke.centraleconomy.domain.ledger.Posting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteLedgerStoreTest {
    private static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir Path temporaryDirectory;
    private SqliteLedgerStore store;

    @BeforeEach
    void setUp() {
        store = new SqliteLedgerStore(temporaryDirectory.resolve("economy.db"));
        store.createAccount(Account.issuanceControl());
        store.createAccount(Account.retiredControl());
        store.createAccount(Account.treasury());
        store.createAccount(Account.taxRevenue());
        store.createAccount(Account.feeRevenue());
        store.createAccount(Account.player(PLAYER_A));
        store.createAccount(Account.player(PLAYER_B));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void commitsJournalAndBalancesAtomically() {
        store.commit(issue(10_000, null, null));
        store.commit(transfer(AccountId.treasury(), AccountId.player(PLAYER_A), 2_500, null, null));

        assertEquals(7_500L, store.balance(AccountId.treasury()));
        assertEquals(2_500L, store.balance(AccountId.player(PLAYER_A)));
        assertEquals(2, store.entryCount());
    }

    @Test
    void insufficientFundsRollsBackEveryLine() {
        store.commit(issue(1_000, null, null));
        JournalEntry tooLarge = JournalEntry.create(UUID.randomUUID(), JournalType.TREASURY_ALLOCATION,
                "two allocations", null, null, Instant.EPOCH.plusSeconds(1), List.of(
                        new Posting(AccountId.treasury(), -1_100),
                        new Posting(AccountId.player(PLAYER_A), 800),
                        new Posting(AccountId.player(PLAYER_B), 300)));

        assertThrows(LedgerException.class, () -> store.commit(tooLarge));
        assertEquals(1_000L, store.balance(AccountId.treasury()));
        assertEquals(0L, store.balance(AccountId.player(PLAYER_A)));
        assertEquals(0L, store.balance(AccountId.player(PLAYER_B)));
        assertEquals(1, store.entryCount());
    }

    @Test
    void sameIdempotencyKeyReturnsOriginalButChangedRequestConflicts() {
        store.commit(issue(2_000, null, null));
        JournalEntry first = store.commit(transfer(AccountId.treasury(), AccountId.player(PLAYER_A),
                500, "bloeco", "grant-1"));
        JournalEntry replay = store.commit(transfer(AccountId.treasury(), AccountId.player(PLAYER_A),
                500, "bloeco", "grant-1"));

        assertEquals(first.id(), replay.id());
        assertEquals(1, store.entriesForKey("bloeco", "grant-1"));
        assertEquals(500L, store.balance(AccountId.player(PLAYER_A)));
        assertThrows(LedgerException.class, () -> store.commit(transfer(
                AccountId.treasury(), AccountId.player(PLAYER_A), 501, "bloeco", "grant-1")));
    }

    @Test
    void integrityRecomputesMaterializedBalancesAndSupply() {
        store.commit(issue(10_000, null, null));
        store.commit(transfer(AccountId.treasury(), AccountId.player(PLAYER_A), 2_500, null, null));

        IntegrityReport report = store.verifyIntegrity();
        assertTrue(report.valid());
        assertEquals(10_000L, store.monetaryTotals().issuedMinor());
        assertEquals(0L, store.monetaryTotals().retiredMinor());
        assertEquals(10_000L, store.monetaryTotals().netSupplyMinor());
    }

    private static JournalEntry issue(long amount, String clientId, String key) {
        return JournalEntry.create(UUID.randomUUID(), JournalType.ISSUE, "approved issue", clientId, key,
                Instant.EPOCH, List.of(new Posting(AccountId.issuanceControl(), -amount),
                        new Posting(AccountId.treasury(), amount)));
    }

    private static JournalEntry transfer(
            AccountId source, AccountId target, long amount, String clientId, String key) {
        return JournalEntry.create(UUID.randomUUID(), JournalType.TREASURY_ALLOCATION, "approved allocation",
                clientId, key, Instant.EPOCH.plusSeconds(1),
                List.of(new Posting(source, -amount), new Posting(target, amount)));
    }
}
