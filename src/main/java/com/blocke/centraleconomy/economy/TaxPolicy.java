package com.blocke.centraleconomy.economy;

import java.util.Objects;

/** Mutable, validated tax policy shared by every settlement service. */
public final class TaxPolicy {
    private int procurementIncomePercent;
    private int transferFeePercent;
    private int transferIncomePercent;
    private int marketConsumptionPercent;

    public TaxPolicy(int procurementIncomePercent, int transferFeePercent, int transferIncomePercent,
                     int marketConsumptionPercent) {
        this.procurementIncomePercent = validate(procurementIncomePercent);
        this.transferFeePercent = validate(transferFeePercent);
        this.transferIncomePercent = validate(transferIncomePercent);
        this.marketConsumptionPercent = validate(marketConsumptionPercent);
    }

    public synchronized int rate(TaxType type) {
        return switch (Objects.requireNonNull(type, "type")) {
            case PROCUREMENT_INCOME -> procurementIncomePercent;
            case TRANSFER_FEE -> transferFeePercent;
            case TRANSFER_INCOME -> transferIncomePercent;
            case MARKET_CONSUMPTION -> marketConsumptionPercent;
        };
    }

    /** Sets a whole percentage. Validation occurs before a live policy is changed. */
    public synchronized void setRate(TaxType type, int percentage) {
        int validated = validate(percentage);
        switch (Objects.requireNonNull(type, "type")) {
            case PROCUREMENT_INCOME -> procurementIncomePercent = validated;
            case TRANSFER_FEE -> transferFeePercent = validated;
            case TRANSFER_INCOME -> transferIncomePercent = validated;
            case MARKET_CONSUMPTION -> marketConsumptionPercent = validated;
        }
    }

    private static int validate(int percentage) {
        if (percentage < 0 || percentage > 100) {
            throw new IllegalArgumentException("tax rate must be between 0 and 100 percent");
        }
        return percentage;
    }
}
