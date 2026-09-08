package com.blocke.centraleconomy.economy;

import java.util.Objects;

/** Mutable, validated tax policy shared by every settlement service. */
public final class TaxPolicy {
    private int transferFeePercent;
    private int transferIncomePercent;

    public TaxPolicy(int transferFeePercent, int transferIncomePercent) {
        this.transferFeePercent = validate(transferFeePercent);
        this.transferIncomePercent = validate(transferIncomePercent);
    }

    public synchronized int rate(TaxType type) {
        return switch (Objects.requireNonNull(type, "type")) {
            case TRANSFER_FEE -> transferFeePercent;
            case TRANSFER_INCOME -> transferIncomePercent;
        };
    }

    /** Sets a whole percentage. Validation occurs before a live policy is changed. */
    public synchronized void setRate(TaxType type, int percentage) {
        int validated = validate(percentage);
        switch (Objects.requireNonNull(type, "type")) {
            case TRANSFER_FEE -> transferFeePercent = validated;
            case TRANSFER_INCOME -> transferIncomePercent = validated;
        }
    }

    private static int validate(int percentage) {
        if (percentage < 0 || percentage > 100) {
            throw new IllegalArgumentException("tax rate must be between 0 and 100 percent");
        }
        return percentage;
    }
}
