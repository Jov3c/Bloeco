package com.blocke.centraleconomy.domain.ledger;

import com.blocke.centraleconomy.domain.account.Account;
import com.blocke.centraleconomy.domain.account.AccountClass;
import com.blocke.centraleconomy.domain.account.AccountId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalEntryTest {
    private static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void acceptsAZeroSumJournal() {
        JournalEntry entry = JournalEntry.create(UUID.randomUUID(), JournalType.PLAYER_TRANSFER,
                "player payment", "bloeco", "pay-1", Instant.EPOCH,
                List.of(new Posting(AccountId.player(PLAYER_A), -100),
                        new Posting(AccountId.player(PLAYER_B), 100)));

        assertEquals(0L, entry.postings().stream().mapToLong(Posting::amountMinor).sum());
        assertEquals("pay-1", entry.idempotencyKey());
    }

    @Test
    void rejectsUnbalancedSingleLineAndOverflowingJournals() {
        assertThrows(LedgerException.class, () -> JournalEntry.create(UUID.randomUUID(),
                JournalType.PLAYER_TRANSFER, "bad", null, null, Instant.EPOCH,
                List.of(new Posting(AccountId.player(PLAYER_A), 100))));
        assertThrows(LedgerException.class, () -> JournalEntry.create(UUID.randomUUID(),
                JournalType.PLAYER_TRANSFER, "bad", null, null, Instant.EPOCH,
                List.of(new Posting(AccountId.player(PLAYER_A), Long.MAX_VALUE),
                        new Posting(AccountId.player(PLAYER_B), 1))));
    }

    @Test
    void definesCentralBankAccountLevelsAndOverdraftRules() {
        Account issuance = Account.issuanceControl();
        Account treasury = Account.treasury();
        Account player = Account.player(PLAYER_A);

        assertEquals(AccountClass.MONETARY_AUTHORITY, issuance.accountClass());
        assertEquals(AccountClass.FISCAL, treasury.accountClass());
        assertEquals(AccountClass.CUSTOMER, player.accountClass());
        assertTrue(issuance.permitsNegativeBalance());
        assertFalse(treasury.permitsNegativeBalance());
        assertFalse(player.permitsNegativeBalance());
        assertNull(treasury.parentId());
        assertEquals(AccountId.treasury(), Account.taxRevenue().parentId());
    }
}
