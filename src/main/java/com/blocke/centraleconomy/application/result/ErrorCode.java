package com.blocke.centraleconomy.application.result;

/** Stable failures safe to expose to commands, GUIs, and future native API consumers. */
public enum ErrorCode {
    INVALID_AMOUNT,
    INVALID_REQUEST,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_FROZEN,
    INSUFFICIENT_FUNDS,
    UNAUTHORIZED,
    POLICY_REJECTED,
    IDEMPOTENCY_CONFLICT,
    INTEGRITY_FAILURE,
    STORAGE_UNAVAILABLE,
    INTERNAL_ERROR
}
