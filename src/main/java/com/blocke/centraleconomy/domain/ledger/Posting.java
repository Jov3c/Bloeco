package com.blocke.centraleconomy.domain.ledger;

import com.blocke.centraleconomy.domain.account.AccountId;

import java.util.Objects;

/** One signed line in a balanced journal entry. */
public record Posting(AccountId accountId, long amountMinor) {
    public Posting {
        Objects.requireNonNull(accountId, "accountId");
        if (amountMinor == 0) {
            throw new IllegalArgumentException("posting amount must not be zero");
        }
    }
}
