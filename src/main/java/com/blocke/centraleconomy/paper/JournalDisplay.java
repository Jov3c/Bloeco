package com.blocke.centraleconomy.paper;

import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.ledger.JournalEntry;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/** Converts immutable journal data into concise Chinese player-facing text. */
final class JournalDisplay {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private JournalDisplay() {}

    static View forAccount(JournalEntry entry, AccountId accountId, ZoneId zoneId) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(zoneId, "zoneId");
        long signedMinor = entry.postings().stream()
                .filter(posting -> posting.accountId().equals(accountId))
                .mapToLong(posting -> posting.amountMinor())
                .sum();
        String direction = signedMinor >= 0 ? "收入：" : "支出：";
        String amount = BigDecimal.valueOf(signedMinor, 2).abs().toPlainString();
        String memo = entry.memo().length() > 48 ? entry.memo().substring(0, 48) + "…" : entry.memo();
        return new View(chineseType(entry), List.of(
                direction + amount + " 金币",
                "时间：" + TIME_FORMAT.withZone(zoneId).format(entry.createdAt()),
                "说明：" + memo));
    }

    private static String chineseType(JournalEntry entry) {
        return switch (entry.type()) {
            case ISSUE -> "货币发行";
            case RETIRE -> "货币回收";
            case TREASURY_ALLOCATION -> "国库拨款";
            case TREASURY_RECLAIM -> "国库收回";
            case PLAYER_TRANSFER -> "玩家转账";
            case PLUGIN_TRANSFER -> "插件结算";
            case REVERSAL -> "账单冲正";
            case LEGACY_EXTERNAL -> "历史账单";
        };
    }

    record View(String title, List<String> lore) {
        View {
            Objects.requireNonNull(title, "title");
            lore = List.copyOf(Objects.requireNonNull(lore, "lore"));
        }
    }
}
