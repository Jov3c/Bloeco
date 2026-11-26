package com.blocke.centraleconomy.config;

import java.util.Locale;

public record ConfigurationSnapshot(
        String storageType,
        String mysqlJdbcUrl,
        String mysqlUsername,
        boolean redisEnabled,
        String redisUri,
        int mysqlPoolSize,
        long redisTtlSeconds,
        long treasuryInitialMinor,
        long playerInitialMinor,
        long bankCapitalMinor,
        int transferFeeBps,
        int incomeTaxBps,
        int purchaseTaxBps,
        int depositRateBps,
        int loanRateBps,
        int reserveRatioBps,
        long maximumLoanMinor,
        int loanTermDays,
        long integrityIntervalMinutes,
        long redisReconnectSeconds) {
    public ConfigurationSnapshot {
        storageType = storageType == null ? "" : storageType.strip().toLowerCase(Locale.ROOT);
    }
}
