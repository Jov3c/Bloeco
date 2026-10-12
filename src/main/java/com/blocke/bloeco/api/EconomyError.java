package com.blocke.bloeco.api;

/** Stable errors exposed to Bloeco-compatible plugins. */
public enum EconomyError {
    INVALID_REQUEST,
    INVALID_AMOUNT,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_FROZEN,
    INSUFFICIENT_FUNDS,
    UNAUTHORIZED,
    CAPABILITY_DENIED,
    POLICY_REJECTED,
    IDEMPOTENCY_CONFLICT,
    INTEGRITY_READ_ONLY,
    STORAGE_UNAVAILABLE,
    RESULT_UNKNOWN,
    INTERNAL_ERROR
}
