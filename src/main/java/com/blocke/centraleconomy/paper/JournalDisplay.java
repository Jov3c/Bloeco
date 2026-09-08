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
        String localizedMemo = localizeKnownMemo(entry.memo());
        String memo = localizedMemo.length() > 48 ? localizedMemo.substring(0, 48) + "…" : localizedMemo;
        return new View(chineseType(entry), List.of(
                direction + amount + " 金币",
                "时间：" + TIME_FORMAT.withZone(zoneId).format(entry.createdAt()),
                "说明：" + memo));
    }

    private static String localizeKnownMemo(String memo) {
        if (memo.equals("Configured starter funds")) return "新玩家启动资金";
        if (memo.equals("Configured initial Treasury supply")) return "初始国库资金";
        if (memo.startsWith("Approved issuance: ")) {
            return "已批准的货币发行：" + localizeKnownMemo(memo.substring("Approved issuance: ".length()));
        }
        if (memo.startsWith("GUI issuance request by ")) {
            return memo.substring("GUI issuance request by ".length()) + " 提交货币发行申请";
        }
        if (memo.startsWith("GUI retirement by ")) {
            return memo.substring("GUI retirement by ".length()) + " 执行货币回收";
        }
        if (memo.startsWith("GUI fiscal rule change by ")) {
            return memo.substring("GUI fiscal rule change by ".length()) + " 修改财政规则";
        }
        if (memo.startsWith("Legacy entry ")) return "历史账单 " + memo.substring("Legacy entry ".length());
        String paymentPrefix = memo.startsWith("Player payment ") ? "Player payment "
                : memo.startsWith("GUI payment ") ? "GUI payment " : null;
        if (paymentPrefix != null) {
            String participants = memo.substring(paymentPrefix.length());
            int separator = participants.indexOf(" -> ");
            if (separator > 0 && separator < participants.length() - 4) {
                return participants.substring(0, separator) + " 向 "
                        + participants.substring(separator + 4) + " 转账";
            }
        }
        return memo;
    }

    private static String chineseType(JournalEntry entry) {
        return switch (entry.type()) {
            case ISSUE -> "货币发行";
            case RETIRE -> "货币回收";
            case TREASURY_ALLOCATION -> "国库拨款";
            case TREASURY_RECLAIM -> "国库收回";
            case PLAYER_TRANSFER -> "玩家转账";
            case PLUGIN_TRANSFER -> "插件结算";
            case BANK_CAPITAL_INJECTION -> "银行注资";
            case BANK_DEPOSIT -> "银行存款";
            case BANK_WITHDRAWAL -> "银行取款";
            case LOAN_DISBURSEMENT -> "贷款发放";
            case LOAN_REPAYMENT -> "贷款还款";
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
