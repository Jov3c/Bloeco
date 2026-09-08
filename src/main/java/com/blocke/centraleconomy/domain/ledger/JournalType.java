package com.blocke.centraleconomy.domain.ledger;

/** Controlled reasons for monetary journal entries. */
public enum JournalType {
    ISSUE,
    RETIRE,
    TREASURY_ALLOCATION,
    TREASURY_RECLAIM,
    PLAYER_TRANSFER,
    PLUGIN_TRANSFER,
    REVERSAL,
    LEGACY_EXTERNAL
}
