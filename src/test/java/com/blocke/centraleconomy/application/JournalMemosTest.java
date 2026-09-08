package com.blocke.centraleconomy.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JournalMemosTest {
    @Test
    void builtInJournalMemosAreChinese() {
        assertEquals("Alice 向 Bob 转账", JournalMemos.playerPayment("Alice", "Bob"));
        assertEquals("Admin 提交货币发行申请", JournalMemos.issuanceRequest("Admin"));
        assertEquals("Admin 执行货币回收", JournalMemos.retirement("Admin"));
        assertEquals("Admin 修改财政规则", JournalMemos.fiscalRuleChange("Admin"));
        assertEquals("已批准的货币发行：初始国库资金", JournalMemos.approvedIssuance("初始国库资金"));
        assertEquals("已批准的货币发行：Admin 提交货币发行申请",
                JournalMemos.approvedIssuance("GUI issuance request by Admin"));
        assertEquals("历史账单 legacy-1", JournalMemos.legacyEntry("legacy-1"));
    }
}
