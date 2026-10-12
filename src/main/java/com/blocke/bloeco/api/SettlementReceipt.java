package com.blocke.bloeco.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Permanent receipt returned only after the authoritative SQL transaction commits. */
public record SettlementReceipt(
        UUID operationId,
        UUID journalId,
        Status status,
        SettlementKind kind,
        UUID playerId,
        InstitutionAccount account,
        MoneyAmount principal,
        MoneyAmount tax,
        MoneyAmount fee,
        MoneyAmount institutionNet,
        String displayMemo,
        Instant committedAt,
        Optional<UUID> originalOperationId) {

    public SettlementReceipt {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(journalId, "journalId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(tax, "tax");
        Objects.requireNonNull(fee, "fee");
        Objects.requireNonNull(institutionNet, "institutionNet");
        Objects.requireNonNull(displayMemo, "displayMemo");
        Objects.requireNonNull(committedAt, "committedAt");
        originalOperationId = Objects.requireNonNull(originalOperationId, "originalOperationId");
    }

    public enum Status { COMMITTED, REVERSED }
}
