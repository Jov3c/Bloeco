package com.blocke.centraleconomy.application;

import java.util.List;
import java.util.Objects;

/** Result of rebuilding balances and journal invariants from authoritative postings. */
public record IntegrityReport(boolean valid, List<String> violations) {
    public IntegrityReport {
        violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
        if (valid == !violations.isEmpty()) {
            throw new IllegalArgumentException("validity must match the violation list");
        }
    }

    public static IntegrityReport validReport() {
        return new IntegrityReport(true, List.of());
    }
}
