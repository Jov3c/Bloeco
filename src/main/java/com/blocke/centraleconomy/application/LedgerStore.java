package com.blocke.centraleconomy.application;

import com.blocke.centraleconomy.domain.account.Account;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.ledger.JournalEntry;

import java.util.Optional;
import java.time.Instant;
import java.util.UUID;

/** Transaction-oriented persistence port implemented by each authoritative SQL backend. */
public interface LedgerStore extends AutoCloseable {
    void createAccount(Account account);
    JournalEntry commit(JournalEntry entry);
    void createIssuanceRequest(IssuanceRecord request);
    IssuanceRecord issuanceRequest(UUID requestId);
    IssuanceRecord approveIssuance(UUID requestId, String approverId, Instant approvedAt);
    JournalEntry commitApprovedIssuance(UUID requestId, JournalEntry entry);
    void setPolicyLimit(String key, long value, String actorId, String memo, Instant changedAt);
    long policyLimit(String key, long defaultValue);
    long issuedSince(Instant since);
    long retiredSince(Instant since);
    long balance(AccountId accountId);
    Optional<JournalEntry> entry(UUID entryId);
    Optional<JournalEntry> idempotentResult(String clientId, String idempotencyKey);
    boolean hasReversal(UUID originalEntryId);
    int entryCount();
    int entriesForKey(String clientId, String idempotencyKey);
    MonetaryTotals monetaryTotals();
    IntegrityReport verifyIntegrity();
    @Override void close();
}
