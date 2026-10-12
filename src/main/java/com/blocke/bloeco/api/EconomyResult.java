package com.blocke.bloeco.api;

import java.util.Objects;

/** Success or safe failure returned by every asynchronous Bloeco API operation. */
public record EconomyResult<T>(T value, EconomyError error, String message) {
    public EconomyResult {
        if (error == null) {
            if (message != null) throw new IllegalArgumentException("success cannot have an error message");
        } else {
            if (value != null) throw new IllegalArgumentException("failure cannot contain a value");
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("failure requires a safe message");
            }
        }
    }

    public static <T> EconomyResult<T> success(T value) {
        return new EconomyResult<>(value, null, null);
    }

    public static <T> EconomyResult<T> failure(EconomyError error, String message) {
        return new EconomyResult<>(null, Objects.requireNonNull(error, "error"), message);
    }

    public boolean isSuccess() {
        return error == null;
    }
}
