package com.blocke.centraleconomy.domain.ledger;

import java.util.Objects;

/** Domain rejection with a stable, non-sensitive reason code. */
public final class LedgerException extends RuntimeException {
    private final Code code;

    public LedgerException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public LedgerException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() { return code; }

    public enum Code {
        INVALID_JOURNAL,
        INVALID_AMOUNT,
        ACCOUNT_NOT_FOUND,
        ACCOUNT_FROZEN,
        INSUFFICIENT_FUNDS,
        IDEMPOTENCY_CONFLICT,
        POLICY_REJECTED,
        INTEGRITY_FAILURE,
        STORAGE_UNAVAILABLE
    }
}
