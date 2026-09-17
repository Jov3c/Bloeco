package com.blocke.centraleconomy.config;

import java.util.ArrayList;
import java.util.List;

/** Validates startup settings before any database or Paper service is created. */
public final class ConfigurationValidator {
    private ConfigurationValidator() {
    }

    public static List<String> validate(ConfigurationSnapshot config) {
        List<String> errors = new ArrayList<>();
        if (config == null) {
            return List.of("配置不能为空。");
        }
        String storage = config.storageType() == null ? "" : config.storageType().trim().toLowerCase();
        if (!storage.equals("mysql") && !storage.equals("sqlite")) {
            errors.add("storage.type 必须是 mysql 或 sqlite。");
        }
        if (storage.equals("mysql")) {
            if (blank(config.mysqlJdbcUrl())) errors.add("MySQL JDBC 地址不能为空。");
            if (blank(config.mysqlUsername())) errors.add("MySQL 用户名不能为空。");
            if (config.mysqlPoolSize() < 2 || config.mysqlPoolSize() > 128) {
                errors.add("MySQL 连接池大小必须在 2 到 128 之间。");
            }
        }
        if (config.redisEnabled()) {
            if (blank(config.redisUri())) errors.add("Redis 地址不能为空。");
            if (config.redisTtlSeconds() < 1 || config.redisTtlSeconds() > 86_400) {
                errors.add("Redis 缓存 TTL 必须在 1 到 86400 秒之间。");
            }
        }
        if (config.treasuryInitialMinor() <= 0 || config.playerInitialMinor() <= 0
                || config.bankCapitalMinor() <= 0 || config.maximumLoanMinor() <= 0) {
            errors.add("国库、玩家初始资金、银行资本和贷款上限必须为正数。");
        }
        int[] basisPoints = {
                config.transferFeeBps(), config.incomeTaxBps(), config.purchaseTaxBps(),
                config.depositRateBps(), config.loanRateBps(), config.reserveRatioBps()
        };
        for (int value : basisPoints) {
            if (value < 0 || value > 10_000) {
                errors.add("税费和利率基点必须在 0 到 10000 之间。");
                break;
            }
        }
        return List.copyOf(errors);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
