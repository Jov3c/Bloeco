package com.blocke.centraleconomy.domain.banking;

import com.blocke.centraleconomy.domain.money.Money;

public record BankSnapshot(
        Money cash, Money depositLiabilities, Money loanAssets, long activeLoans,
        long overdueLoans, BankingPolicy policy) {}
