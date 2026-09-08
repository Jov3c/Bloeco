package com.blocke.centraleconomy.domain.account;

import java.util.Objects;
import java.util.UUID;

/** Immutable account definition; closing an account never deletes its history. */
public record Account(
        AccountId id,
        AccountClass accountClass,
        OwnerType ownerType,
        String ownerId,
        String purpose,
        Status status,
        boolean permitsNegativeBalance,
        AccountId parentId) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountClass, "accountClass");
        Objects.requireNonNull(ownerType, "ownerType");
        Objects.requireNonNull(status, "status");
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 128) {
            throw new IllegalArgumentException("owner id must contain 1 through 128 characters");
        }
        if (purpose == null || purpose.isBlank() || purpose.length() > 64) {
            throw new IllegalArgumentException("purpose must contain 1 through 64 characters");
        }
        if (permitsNegativeBalance && accountClass != AccountClass.MONETARY_AUTHORITY) {
            throw new IllegalArgumentException("only monetary authority control accounts may be negative");
        }
    }

    public static Account issuanceControl() {
        return new Account(AccountId.issuanceControl(), AccountClass.MONETARY_AUTHORITY,
                OwnerType.SYSTEM, "bloeco", "issuance", Status.ACTIVE, true, null);
    }

    public static Account retiredControl() {
        return new Account(AccountId.retiredControl(), AccountClass.MONETARY_AUTHORITY,
                OwnerType.SYSTEM, "bloeco", "retired", Status.ACTIVE, false, null);
    }

    public static Account treasury() {
        return fiscal(AccountId.treasury(), "treasury");
    }

    public static Account taxRevenue() {
        return fiscal(AccountId.taxRevenue(), "tax");
    }

    public static Account feeRevenue() {
        return fiscal(AccountId.feeRevenue(), "fee");
    }

    private static Account fiscal(AccountId id, String purpose) {
        return new Account(id, AccountClass.FISCAL, OwnerType.SYSTEM,
                "bloeco", purpose, Status.ACTIVE, false, AccountId.treasury());
    }

    public static Account player(UUID playerId) {
        UUID owner = Objects.requireNonNull(playerId, "playerId");
        return new Account(AccountId.player(owner), AccountClass.CUSTOMER, OwnerType.PLAYER,
                owner.toString(), "wallet", Status.ACTIVE, false, null);
    }

    public enum OwnerType { SYSTEM, PLUGIN, PLAYER }
    public enum Status { ACTIVE, FROZEN, CLOSED }
}
