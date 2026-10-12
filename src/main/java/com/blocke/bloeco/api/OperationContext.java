package com.blocke.bloeco.api;

import java.util.Objects;

/** Immutable business identity and player-facing explanation for one retryable write. */
public record OperationContext(
        String businessType,
        String businessReference,
        String displayMemo,
        String idempotencyKey) {

    public OperationContext {
        businessType = checked(businessType, "businessType", 128);
        if (!businessType.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("businessType must be a safe ASCII identifier");
        }
        businessReference = checked(businessReference, "businessReference", 255);
        displayMemo = checked(displayMemo, "displayMemo", 256);
        idempotencyKey = checked(idempotencyKey, "idempotencyKey", 255);
        if (!idempotencyKey.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,254}")) {
            throw new IllegalArgumentException("idempotencyKey contains unsafe characters");
        }
    }

    private static String checked(String value, String label, int maximumLength) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(label + " must contain 1 through " + maximumLength + " characters");
        }
        return normalized;
    }
}
