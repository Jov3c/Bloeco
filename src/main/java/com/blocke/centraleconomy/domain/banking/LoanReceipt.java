package com.blocke.centraleconomy.domain.banking;

import com.blocke.centraleconomy.domain.money.Money;
import java.time.Instant;
import java.util.UUID;

public record LoanReceipt(UUID loanId, UUID journalId, Money principal, Money totalDue, Instant dueAt) {}
