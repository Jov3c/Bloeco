package com.blocke.centraleconomy.application;

import java.util.Objects;

/** Chinese player-facing descriptions written to Bloeco journals and policy records. */
public final class JournalMemos {
    public static final String STARTER_FUNDS = "新玩家启动资金";
    public static final String INITIAL_TREASURY_SUPPLY = "初始国库资金";

    private JournalMemos() {}

    public static String playerPayment(String senderName, String recipientName) {
        return text(senderName, "senderName") + " 向 " + text(recipientName, "recipientName") + " 转账";
    }

    public static String issuanceRequest(String actorName) {
        return text(actorName, "actorName") + " 提交货币发行申请";
    }

    public static String retirement(String actorName) {
        return text(actorName, "actorName") + " 执行货币回收";
    }

    public static String fiscalRuleChange(String actorName) {
        return text(actorName, "actorName") + " 修改财政规则";
    }

    public static String approvedIssuance(String reason) {
        String localizedReason = text(reason, "reason");
        if ("Configured initial Treasury supply".equals(localizedReason)) {
            localizedReason = INITIAL_TREASURY_SUPPLY;
        } else if (localizedReason.startsWith("GUI issuance request by ")) {
            localizedReason = issuanceRequest(localizedReason.substring("GUI issuance request by ".length()));
        }
        return "已批准的货币发行：" + localizedReason;
    }

    public static String legacyEntry(String legacyId) {
        return "历史账单 " + text(legacyId, "legacyId");
    }

    private static String text(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
        return value;
    }
}
