package com.blocke.centraleconomy.ledger;

import java.util.Objects;
import java.util.UUID;

/** Stable identifier for an account recorded by the ledger. */
public record AccountId(String value) {
    private static final AccountId TREASURY = new AccountId("TREASURY");
    private static final AccountId ISSUANCE = new AccountId("ISSUANCE");
    private static final AccountId BURN = new AccountId("BURN");
    private static final AccountId EXTERNAL_CREDIT = new AccountId("EXTERNAL_CREDIT");
    private static final AccountId EXTERNAL_DEBIT = new AccountId("EXTERNAL_DEBIT");

    public AccountId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("account id must not be blank");
        }
    }

    public static AccountId treasury() {
        return TREASURY;
    }

    public static AccountId issuance() {
        return ISSUANCE;
    }

    public static AccountId burn() {
        return BURN;
    }

    /** Unfunded source account used for explicitly recorded external Vault credits. */
    public static AccountId externalCredit() {
        return EXTERNAL_CREDIT;
    }

    /** Sink account used for explicitly recorded external Vault debits. */
    public static AccountId externalDebit() {
        return EXTERNAL_DEBIT;
    }

    public static AccountId player(UUID playerId) {
        return new AccountId("PLAYER:" + Objects.requireNonNull(playerId, "playerId"));
    }

    public boolean isIssuance() {
        return equals(ISSUANCE);
    }

    public boolean isUnfundedSource() {
        return isIssuance() || equals(EXTERNAL_CREDIT);
    }
}
