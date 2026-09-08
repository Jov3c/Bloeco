package com.blocke.centraleconomy.domain.banking;

import com.blocke.centraleconomy.domain.money.Money;
import java.util.UUID;

public record BankingReceipt(UUID operationId, UUID journalId, Money amount) {}
