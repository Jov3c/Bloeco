package com.blocke.bloeco.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Snapshot of a player-fund reservation owned by the connected institution. */
public record ReservationReceipt(
        UUID reservationId,
        Status status,
        UUID playerId,
        MoneyAmount amount,
        Instant expiresAt,
        Instant createdAt) {

    public ReservationReceipt {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public enum Status { ACTIVE, CAPTURED, RELEASED, EXPIRED }
}
