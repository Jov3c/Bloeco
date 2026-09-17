package com.blocke.centraleconomy.config;

/** Immutable startup configuration values used by the pure validator. */
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
        long maximumLoanMinor) {
}
