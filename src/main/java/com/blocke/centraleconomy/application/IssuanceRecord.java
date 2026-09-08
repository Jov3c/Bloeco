package com.blocke.centraleconomy.application;

import com.blocke.centraleconomy.domain.money.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persistent state of the request-approve-execute issuance workflow. */
public record IssuanceRecord(
        UUID requestId,
        Money amount,
        Status status,
        String reason,
        String requesterId,
        String approverId,
        Instant requestedAt,
        Instant approvedAt,
        UUID executedEntryId) {

    public IssuanceRecord {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(requestedAt, "requestedAt");
        requireText(reason, "reason", 256);
        requireText(requesterId, "requesterId", 128);
        if (amount.minor() == 0) throw new IllegalArgumentException("issuance amount must be positive");
    }

    private static void requireText(String value, String label, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(label + " must contain 1 through " + maximum + " characters");
        }
    }

    public enum Status { REQUESTED, APPROVED, EXECUTED }
}
