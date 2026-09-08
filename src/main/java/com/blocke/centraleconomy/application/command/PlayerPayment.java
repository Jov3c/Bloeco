package com.blocke.centraleconomy.application.command;

import com.blocke.centraleconomy.domain.money.Money;

import java.util.Objects;
import java.util.UUID;

/** Player-authorized transfer command submitted to the central clearing service. */
public record PlayerPayment(UUID senderId, UUID recipientId, Money principal, String memo, String idempotencyKey) {
    public PlayerPayment {
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(principal, "principal");
        if (senderId.equals(recipientId)) throw new IllegalArgumentException("player cannot pay self");
        if (principal.minor() == 0) throw new IllegalArgumentException("payment principal must be positive");
        requireText(memo, "memo", 256);
        requireText(idempotencyKey, "idempotencyKey", 128);
    }

    private static void requireText(String value, String label, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(label + " must contain 1 through " + maximum + " characters");
        }
    }
}
