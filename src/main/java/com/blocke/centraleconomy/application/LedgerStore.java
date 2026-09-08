package com.blocke.centraleconomy.application;

import com.blocke.centraleconomy.domain.account.Account;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.ledger.JournalEntry;

import java.util.Optional;
import java.util.UUID;

/** Transaction-oriented persistence port implemented by each authoritative SQL backend. */
public interface LedgerStore extends AutoCloseable {
    void createAccount(Account account);
    JournalEntry commit(JournalEntry entry);
    long balance(AccountId accountId);
    Optional<JournalEntry> entry(UUID entryId);
    Optional<JournalEntry> idempotentResult(String clientId, String idempotencyKey);
    int entryCount();
    int entriesForKey(String clientId, String idempotencyKey);
    MonetaryTotals monetaryTotals();
    IntegrityReport verifyIntegrity();
    @Override void close();
}
