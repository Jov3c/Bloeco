package com.blocke.centraleconomy.ledger;

import com.blocke.centraleconomy.money.Money;

import java.util.List;
import java.util.Objects;

/** Authoritative persistence boundary for account balances and ledger entries. */
public interface LedgerRepository extends AutoCloseable {
    void transfer(AccountId debitAccount, AccountId creditAccount, Money amount, TransactionType transactionType, String memo);

    void transferBatch(List<Posting> postings);

    Money balance(AccountId accountId);

    @Override
    void close();

    record Posting(
            AccountId debitAccount,
            AccountId creditAccount,
            Money amount,
            TransactionType transactionType,
            String memo) {
        public Posting {
            Objects.requireNonNull(debitAccount, "debitAccount");
            Objects.requireNonNull(creditAccount, "creditAccount");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(transactionType, "transactionType");
            Objects.requireNonNull(memo, "memo");
            if (amount.cents() == 0) {
                throw new IllegalArgumentException("ledger entries must have a positive amount");
            }
            if (debitAccount.isIssuance() && transactionType != TransactionType.ISSUE) {
                throw new IllegalArgumentException("issuance may only fund ISSUE transactions");
            }
            if (debitAccount.equals(AccountId.externalCredit()) && transactionType != TransactionType.EXTERNAL_CREDIT) {
                throw new IllegalArgumentException("external credit may only fund EXTERNAL_CREDIT transactions");
            }
            if (creditAccount.isUnfundedSource()) {
                throw new IllegalArgumentException("issuance may only be a debit account");
            }
        }
    }
}
