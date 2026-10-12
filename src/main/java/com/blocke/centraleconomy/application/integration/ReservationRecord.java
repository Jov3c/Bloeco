package com.blocke.centraleconomy.application.integration;

import com.blocke.centraleconomy.domain.money.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persisted player-fund reservation including its central tax snapshot. */
public record ReservationRecord(
        UUID reservationId, String clientId, UUID playerId, Money principal, Money tax,
        UUID taxRuleVersion, Status status, Instant expiresAt, Instant createdAt, Instant updatedAt,
        String idempotencyKey, String fingerprint) {
    public ReservationRecord {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(tax, "tax");
        Objects.requireNonNull(taxRuleVersion, "taxRuleVersion");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(fingerprint, "fingerprint");
    }
    public Money reservedTotal() { return principal.plus(tax); }
    public enum Status { ACTIVE, CAPTURED, RELEASED, EXPIRED }
}
