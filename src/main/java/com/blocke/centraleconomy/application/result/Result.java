package com.blocke.centraleconomy.application.result;

import java.util.Objects;

/** Success or safe failure across the asynchronous application boundary. */
public record Result<T>(T value, ErrorCode errorCode, String message) {
    public Result {
        if (errorCode == null) {
            if (message != null) throw new IllegalArgumentException("success must not have an error message");
        } else if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("failure must have a safe message");
        }
    }

    public static <T> Result<T> success(T value) {
        return new Result<>(value, null, null);
    }

    public static <T> Result<T> failure(ErrorCode code, String message) {
        return new Result<>(null, Objects.requireNonNull(code, "code"), message);
    }

    public boolean isSuccess() {
        return errorCode == null;
    }
}
