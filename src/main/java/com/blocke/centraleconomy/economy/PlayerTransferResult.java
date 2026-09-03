package com.blocke.centraleconomy.economy;

import com.blocke.centraleconomy.money.Money;

import java.util.Objects;

/** Immutable breakdown of a completed player-to-player payment. */
public record PlayerTransferResult(
        Money amount,
        Money transferFee,
        Money incomeTax,
        Money recipientNet,
        Money senderDebit) {
    public PlayerTransferResult {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(transferFee, "transferFee");
        Objects.requireNonNull(incomeTax, "incomeTax");
        Objects.requireNonNull(recipientNet, "recipientNet");
        Objects.requireNonNull(senderDebit, "senderDebit");
        if (recipientNet.cents() != amount.cents() - incomeTax.cents()) {
            throw new IllegalArgumentException("recipient net must equal amount minus income tax");
        }
        if (senderDebit.cents() != amount.cents() + transferFee.cents()) {
            throw new IllegalArgumentException("sender debit must equal amount plus transfer fee");
        }
    }
}
