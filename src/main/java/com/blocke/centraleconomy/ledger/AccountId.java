package com.blocke.centraleconomy.ledger;

import java.util.Objects;
import java.util.UUID;

/** Stable identifier for an account recorded by the ledger. */
public record AccountId(String value) {
    private static final AccountId TREASURY = new AccountId("TREASURY");
    private static final AccountId ISSUANCE = new AccountId("ISSUANCE");
    private static final AccountId BURN = new AccountId("BURN");

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

    public static AccountId player(UUID playerId) {
        return new AccountId("PLAYER:" + Objects.requireNonNull(playerId, "playerId"));
    }

    public boolean isIssuance() {
        return equals(ISSUANCE);
    }
}
