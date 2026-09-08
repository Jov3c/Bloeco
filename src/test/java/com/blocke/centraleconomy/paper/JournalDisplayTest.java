package com.blocke.centraleconomy.paper;

import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.ledger.JournalEntry;
import com.blocke.centraleconomy.domain.ledger.JournalType;
import com.blocke.centraleconomy.domain.ledger.Posting;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JournalDisplayTest {
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final AccountId PLAYER = AccountId.player(PLAYER_ID);
    private static final Instant TIME = Instant.parse("2026-09-08T12:30:45Z");

    @Test
    void expenseShowsChineseTypeAmountThenTimeWithoutCredential() {
        UUID credential = UUID.fromString("10000000-0000-0000-0000-000000000001");
        JournalEntry entry = JournalEntry.create(credential, JournalType.PLAYER_TRANSFER,
                "向玩家付款", "bloeco.player", "payment-1", TIME,
                List.of(new Posting(PLAYER, -10_000), new Posting(AccountId.treasury(), 10_000)));

        JournalDisplay.View view = JournalDisplay.forAccount(entry, PLAYER, ZoneId.of("Asia/Shanghai"));

        assertEquals("玩家转账", view.title());
        assertEquals(List.of("支出：100.00 金币", "时间：2026-09-08 20:30:45", "说明：向玩家付款"), view.lore());
        assertFalse(String.join(" ", view.lore()).contains(credential.toString()));
    }

    @Test
    void incomeUsesIncomeLabel() {
        JournalEntry entry = JournalEntry.create(UUID.randomUUID(), JournalType.TREASURY_ALLOCATION,
                "新玩家启动资金", "bloeco.treasury", "starter-1", TIME,
                List.of(new Posting(AccountId.treasury(), -10_000), new Posting(PLAYER, 10_000)));

        JournalDisplay.View view = JournalDisplay.forAccount(entry, PLAYER, ZoneId.of("Asia/Shanghai"));

        assertEquals("国库拨款", view.title());
        assertEquals("收入：100.00 金币", view.lore().get(0));
    }

    @Test
    void knownLegacyEnglishMemoIsTranslatedForExistingJournal() {
        JournalEntry entry = JournalEntry.create(UUID.randomUUID(), JournalType.PLAYER_TRANSFER,
                "Player payment Alice -> Bob", "bloeco.player", "old-payment", TIME,
                List.of(new Posting(PLAYER, -10_000), new Posting(AccountId.treasury(), 10_000)));

        JournalDisplay.View view = JournalDisplay.forAccount(entry, PLAYER, ZoneId.of("Asia/Shanghai"));

        assertEquals("说明：Alice 向 Bob 转账", view.lore().get(2));
    }
}
