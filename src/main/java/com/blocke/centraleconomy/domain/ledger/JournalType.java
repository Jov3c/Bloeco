package com.blocke.centraleconomy.domain.ledger;

/** Controlled reasons for monetary journal entries. */
public enum JournalType {
    ISSUE,
    RETIRE,
    TREASURY_ALLOCATION,
    TREASURY_RECLAIM,
    PLAYER_TRANSFER,
    PLUGIN_TRANSFER,
    BANK_CAPITAL_INJECTION,
    BANK_DEPOSIT,
    BANK_WITHDRAWAL,
    LOAN_DISBURSEMENT,
    LOAN_REPAYMENT,
    REVERSAL,
    LEGACY_EXTERNAL
}
