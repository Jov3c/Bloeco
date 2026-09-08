package com.blocke.centraleconomy.domain.account;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Stable account identifier used in every journal posting. */
public record AccountId(String value) implements Comparable<AccountId> {
    private static final AccountId ISSUANCE = new AccountId("monetary:issuance");
    private static final AccountId RETIRED = new AccountId("monetary:retired");
    private static final AccountId TREASURY = new AccountId("fiscal:treasury");
    private static final AccountId TAX = new AccountId("fiscal:tax");
    private static final AccountId FEE = new AccountId("fiscal:fee");

    public AccountId {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("account id must contain 1 through 128 characters");
        }
        value = value.toLowerCase(Locale.ROOT);
    }

    public static AccountId issuanceControl() { return ISSUANCE; }
    public static AccountId retiredControl() { return RETIRED; }
    public static AccountId treasury() { return TREASURY; }
    public static AccountId taxRevenue() { return TAX; }
    public static AccountId feeRevenue() { return FEE; }

    public static AccountId player(UUID playerId) {
        return new AccountId("player:" + Objects.requireNonNull(playerId, "playerId") + ":wallet");
    }

    public static AccountId plugin(String clientId, String purpose) {
        return new AccountId("plugin:" + segment(clientId, "clientId") + ":" + segment(purpose, "purpose"));
    }

    private static String segment(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException(label + " must be a safe identifier");
        }
        return normalized;
    }

    @Override
    public int compareTo(AccountId other) {
        return value.compareTo(other.value);
    }
}
