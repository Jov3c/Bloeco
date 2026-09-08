package com.blocke.centraleconomy.paper;

import com.blocke.centraleconomy.application.result.ErrorCode;
import com.blocke.centraleconomy.application.result.Result;
import com.blocke.centraleconomy.domain.money.Money;

import java.math.BigDecimal;

/** User-facing monetary and error formatting without floating point. */
public final class MessageFormatter {
    private MessageFormatter() {}

    public static String money(Money money) {
        return moneyMinor(money.minor());
    }

    public static String moneyMinor(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }

    public static String error(Result<?> result) {
        if (result.isSuccess()) throw new IllegalArgumentException("success has no error");
        ErrorCode code = result.errorCode();
        return switch (code) {
            case INSUFFICIENT_FUNDS -> "余额不足，操作未入账。";
            case IDEMPOTENCY_CONFLICT -> "该业务编号已被其他请求使用。";
            case POLICY_REJECTED -> "经济政策拒绝了该操作：" + result.message();
            case ACCOUNT_FROZEN -> "账户已冻结，操作未入账。";
            case STORAGE_UNAVAILABLE -> "经济中心暂时不可用，请稍后重试。";
            default -> "经济操作失败：" + result.message();
        };
    }
}
