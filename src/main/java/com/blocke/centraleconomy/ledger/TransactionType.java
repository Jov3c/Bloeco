package com.blocke.centraleconomy.ledger;

/** Business reason attached to an immutable ledger entry. */
public enum TransactionType {
    ISSUE,
    BURN,
    TREASURY_ALLOCATION,
    PLAYER_TRANSFER,
    TRANSFER_FEE,
    PERSONAL_INCOME_TAX,
    VAULT_DEPOSIT,
    VAULT_WITHDRAW,
    EXTERNAL_CREDIT,
    EXTERNAL_DEBIT,
    TRANSFER
}
