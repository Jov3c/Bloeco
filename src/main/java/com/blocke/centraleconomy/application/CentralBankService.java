package com.blocke.centraleconomy.application;

import com.blocke.centraleconomy.domain.account.Account;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.ledger.JournalEntry;
import com.blocke.centraleconomy.domain.ledger.JournalType;
import com.blocke.centraleconomy.domain.ledger.LedgerException;
import com.blocke.centraleconomy.domain.ledger.Posting;
import com.blocke.centraleconomy.domain.money.Money;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Monetary authority and Treasury use cases; no ordinary operation may mint money. */
public final class CentralBankService {
    public static final String PER_OPERATION_ISSUANCE_LIMIT = "issuance.per-operation-minor";
    public static final String DAILY_ISSUANCE_LIMIT = "issuance.daily-minor";
    public static final String ROLLING_GROWTH_LIMIT_BPS = "issuance.rolling-30-day-growth-bps";
    private static final String BOOTSTRAP_REQUESTER = "system:bootstrap-requester";
    private static final String BOOTSTRAP_APPROVER = "system:bootstrap-approver";
    private static final String BOOTSTRAP_REASON = "Configured initial Treasury supply";
    private static final String BOOTSTRAP_KEY = "bootstrap:treasury:v1";
    private static final UUID BOOTSTRAP_REQUEST_ID = UUID.nameUUIDFromBytes(
            "bloeco:bootstrap:treasury:v1".getBytes(StandardCharsets.UTF_8));

    private final LedgerStore store;
    private final Clock clock;

    public CentralBankService(LedgerStore store) {
        this(store, Clock.systemUTC());
    }

    public CentralBankService(LedgerStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void initializeCentralAccounts() {
        store.createAccount(Account.issuanceControl());
        store.createAccount(Account.retiredControl());
        store.createAccount(Account.treasury());
        store.createAccount(Account.taxRevenue());
        store.createAccount(Account.feeRevenue());
    }

    /** Creates the configured genesis supply only while the journal is still empty. */
    public Optional<JournalReceipt> bootstrapTreasury(Money amount) {
        requirePositive(amount);
        Optional<IssuanceRecord> existing = store.findIssuanceRequest(BOOTSTRAP_REQUEST_ID);
        if (existing.isEmpty()) {
            if (store.entryCount() != 0) return Optional.empty();
            store.createIssuanceRequest(new IssuanceRecord(BOOTSTRAP_REQUEST_ID, amount,
                    IssuanceRecord.Status.REQUESTED, BOOTSTRAP_REASON, BOOTSTRAP_REQUESTER,
                    null, clock.instant(), null, null));
        } else if (!existing.get().requesterId().equals(BOOTSTRAP_REQUESTER)
                || !existing.get().reason().equals(BOOTSTRAP_REASON)) {
            throw new LedgerException(LedgerException.Code.IDEMPOTENCY_CONFLICT,
                    "Treasury bootstrap configuration conflicts with the existing genesis request");
        }

        IssuanceRecord request = store.issuanceRequest(BOOTSTRAP_REQUEST_ID);
        if (request.status() == IssuanceRecord.Status.REQUESTED) {
            request = approveIssuance(BOOTSTRAP_REQUEST_ID, BOOTSTRAP_APPROVER);
        }
        if (request.status() != IssuanceRecord.Status.APPROVED
                && request.status() != IssuanceRecord.Status.EXECUTED) {
            throw policyRejected("Treasury bootstrap request is not executable");
        }
        return Optional.of(executeIssuance(BOOTSTRAP_REQUEST_ID, BOOTSTRAP_REQUESTER, BOOTSTRAP_KEY));
    }

    /** Allocates starter money from Treasury exactly once for each player. */
    public JournalReceipt grantStarterFunds(UUID playerId, Money amount) {
        Objects.requireNonNull(playerId, "playerId");
        requirePositive(amount);
        store.createAccount(Account.player(playerId));
        return allocateFromTreasury(AccountId.player(playerId), amount, "system:starter-funds",
                "Configured starter funds", "starter:" + playerId + ":v1");
    }

    public UUID requestIssuance(Money amount, String requesterId, String reason) {
        requirePositive(amount);
        UUID requestId = UUID.randomUUID();
        store.createIssuanceRequest(new IssuanceRecord(requestId, amount, IssuanceRecord.Status.REQUESTED,
                reason, requesterId, null, clock.instant(), null, null));
        return requestId;
    }

    public IssuanceRecord approveIssuance(UUID requestId, String approverId) {
        return store.approveIssuance(Objects.requireNonNull(requestId, "requestId"),
                requireText(approverId, "approverId"), clock.instant());
    }

    public JournalReceipt executeIssuance(UUID requestId, String actorId, String idempotencyKey) {
        requireText(actorId, "actorId");
        String key = requireText(idempotencyKey, "idempotencyKey");
        IssuanceRecord request = store.issuanceRequest(Objects.requireNonNull(requestId, "requestId"));
        if (request.status() == IssuanceRecord.Status.EXECUTED) {
            return store.idempotentResult("bloeco.monetary", key)
                    .filter(entry -> entry.id().equals(request.executedEntryId()))
                    .map(entry -> new JournalReceipt(entry.id()))
                    .orElseThrow(() -> new LedgerException(LedgerException.Code.IDEMPOTENCY_CONFLICT,
                            "issuance was already executed with a different idempotency key"));
        }
        if (request.status() != IssuanceRecord.Status.APPROVED) {
            throw policyRejected("issuance request is not approved");
        }
        validateIssuancePolicy(request.amount());
        JournalEntry entry = JournalEntry.create(UUID.randomUUID(), JournalType.ISSUE,
                "Approved issuance: " + request.reason(), "bloeco.monetary", key,
                clock.instant(), List.of(
                        new Posting(AccountId.issuanceControl(), -request.amount().minor()),
                        new Posting(AccountId.treasury(), request.amount().minor())));
        return new JournalReceipt(store.commitApprovedIssuance(requestId, entry).id());
    }

    public JournalReceipt retireFromTreasury(
            Money amount, String actorId, String memo, String idempotencyKey) {
        requirePositive(amount);
        requireText(actorId, "actorId");
        JournalEntry entry = JournalEntry.create(UUID.randomUUID(), JournalType.RETIRE,
                requireText(memo, "memo"), "bloeco.monetary", requireText(idempotencyKey, "idempotencyKey"),
                clock.instant(), List.of(new Posting(AccountId.treasury(), -amount.minor()),
                        new Posting(AccountId.retiredControl(), amount.minor())));
        return new JournalReceipt(store.commit(entry).id());
    }

    public JournalReceipt allocateFromTreasury(
            AccountId destination, Money amount, String actorId, String memo, String idempotencyKey) {
        requirePositive(amount);
        requireText(actorId, "actorId");
        JournalEntry entry = JournalEntry.create(UUID.randomUUID(), JournalType.TREASURY_ALLOCATION,
                requireText(memo, "memo"), "bloeco.treasury", requireText(idempotencyKey, "idempotencyKey"),
                clock.instant(), List.of(new Posting(AccountId.treasury(), -amount.minor()),
                        new Posting(Objects.requireNonNull(destination, "destination"), amount.minor())));
        return new JournalReceipt(store.commit(entry).id());
    }

    public JournalReceipt adjustPlayerBalance(
            UUID playerId, Money target, String actorId, String memo, String idempotencyKey) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(target, "target");
        requireText(actorId, "actorId");
        store.createAccount(Account.player(playerId));
        AccountId player = AccountId.player(playerId);
        long current = store.balance(player);
        if (current == target.minor()) {
            throw new LedgerException(LedgerException.Code.INVALID_AMOUNT, "target balance already matches account");
        }
        if (current < target.minor()) {
            return allocateFromTreasury(player, Money.ofMinor(Math.subtractExact(target.minor(), current)),
                    actorId, memo, idempotencyKey);
        }
        long amount = Math.subtractExact(current, target.minor());
        JournalEntry entry = JournalEntry.create(UUID.randomUUID(), JournalType.TREASURY_RECLAIM,
                requireText(memo, "memo"), "bloeco.treasury", requireText(idempotencyKey, "idempotencyKey"),
                clock.instant(), List.of(new Posting(player, -amount), new Posting(AccountId.treasury(), amount)));
        return new JournalReceipt(store.commit(entry).id());
    }

    public JournalReceipt reverse(UUID originalEntryId, String actorId, String memo, String idempotencyKey) {
        requireText(actorId, "actorId");
        if (store.hasReversal(Objects.requireNonNull(originalEntryId, "originalEntryId"))) {
            throw new LedgerException(LedgerException.Code.INVALID_JOURNAL,
                    "journal has already been reversed");
        }
        JournalEntry original = store.entry(originalEntryId)
                .orElseThrow(() -> new LedgerException(LedgerException.Code.INVALID_JOURNAL, "journal does not exist"));
        List<Posting> reversed = original.postings().stream()
                .map(line -> new Posting(line.accountId(), Math.negateExact(line.amountMinor())))
                .toList();
        JournalEntry reversal = JournalEntry.reversal(UUID.randomUUID(), original.id(), requireText(memo, "memo"),
                "bloeco.audit", requireText(idempotencyKey, "idempotencyKey"), clock.instant(), reversed);
        return new JournalReceipt(store.commit(reversal).id());
    }

    public void setPolicyLimit(String key, long value, String actorId, String memo) {
        if (value < 0) throw new IllegalArgumentException("policy limit must not be negative");
        store.setPolicyLimit(requireText(key, "key"), value, requireText(actorId, "actorId"),
                requireText(memo, "memo"), clock.instant());
    }

    private void validateIssuancePolicy(Money amount) {
        long operationLimit = store.policyLimit(PER_OPERATION_ISSUANCE_LIMIT, Long.MAX_VALUE);
        if (amount.minor() > operationLimit) throw policyRejected("issuance exceeds per-operation limit");

        Instant dayStart = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        long dailyProjected = Math.addExact(store.issuedSince(dayStart), amount.minor());
        if (dailyProjected > store.policyLimit(DAILY_ISSUANCE_LIMIT, Long.MAX_VALUE)) {
            throw policyRejected("issuance exceeds daily limit");
        }

        Instant rollingStart = clock.instant().minus(Duration.ofDays(30));
        long netChange = Math.subtractExact(store.issuedSince(rollingStart), store.retiredSince(rollingStart));
        long currentSupply = store.monetaryTotals().netSupplyMinor();
        long baseSupply = Math.subtractExact(currentSupply, netChange);
        if (baseSupply > 0) {
            int growthBps = Math.toIntExact(store.policyLimit(ROLLING_GROWTH_LIMIT_BPS, 10_000));
            long allowed = Money.ofMinor(baseSupply).percentage(growthBps).minor();
            if (Math.addExact(netChange, amount.minor()) > allowed) {
                throw policyRejected("issuance exceeds rolling supply growth limit");
            }
        }
    }

    private static void requirePositive(Money amount) {
        Objects.requireNonNull(amount, "amount");
        if (amount.minor() == 0) {
            throw new LedgerException(LedgerException.Code.INVALID_AMOUNT, "amount must be positive");
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(label + " must contain 1 through 256 characters");
        }
        return value;
    }

    private static LedgerException policyRejected(String message) {
        return new LedgerException(LedgerException.Code.POLICY_REJECTED, message);
    }
}
