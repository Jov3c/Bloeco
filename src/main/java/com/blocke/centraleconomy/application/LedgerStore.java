package com.blocke.centraleconomy.application;

import com.blocke.centraleconomy.domain.account.Account;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.account.AccountClass;
import com.blocke.centraleconomy.domain.ledger.JournalEntry;
import com.blocke.centraleconomy.domain.ledger.JournalType;
import com.blocke.centraleconomy.domain.tax.TaxCategory;
import com.blocke.centraleconomy.domain.tax.TaxRule;

import java.util.Optional;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.Map;

/** Transaction-oriented persistence port implemented by each authoritative SQL backend. */
public interface LedgerStore extends AutoCloseable {
    void createAccount(Account account);
    JournalEntry commit(JournalEntry entry);
    void createIssuanceRequest(IssuanceRecord request);
    Optional<IssuanceRecord> findIssuanceRequest(UUID requestId);
    IssuanceRecord issuanceRequest(UUID requestId);
    IssuanceRecord approveIssuance(UUID requestId, String approverId, Instant approvedAt);
    JournalEntry commitApprovedIssuance(UUID requestId, JournalEntry entry);
    void setPolicyLimit(String key, long value, String actorId, String memo, Instant changedAt);
    long policyLimit(String key, long defaultValue);
    long issuedSince(Instant since);
    long retiredSince(Instant since);
    TaxRule changeTaxRule(TaxRule rule);
    Optional<TaxRule> currentTaxRule(TaxCategory category, Instant at);
    Optional<TaxRule> taxRule(UUID versionId);
    int taxRuleCount();
    PlayerSettlement commitPlayerSettlement(JournalEntry entry, PlayerSettlement settlement);
    Optional<PlayerSettlement> playerSettlement(String clientId, String idempotencyKey);
    long balance(AccountId accountId);
    Optional<JournalEntry> entry(UUID entryId);
    Optional<JournalEntry> idempotentResult(String clientId, String idempotencyKey);
    boolean hasReversal(UUID originalEntryId);
    int entryCount();
    int entriesForKey(String clientId, String idempotencyKey);
    MonetaryTotals monetaryTotals();
    Map<AccountClass, Long> accountClassTotals();
    long playerCirculation();
    Map<JournalType, Long> journalVolumeSince(Instant since);
    List<JournalEntry> recentEntries(Optional<AccountId> accountId, int limit);
    IntegrityReport verifyIntegrity();
    @Override void close();
}
