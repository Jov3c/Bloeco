package com.blocke.centraleconomy.domain.banking;

import com.blocke.centraleconomy.domain.money.Money;
import java.util.UUID;

public record BankingPlayerSnapshot(
        Money wallet, Money deposit, Money loanDebt, boolean hasOverdueLoan, String creditGrade,
        UUID nextLoanId, Money nextLoanDue) {}
