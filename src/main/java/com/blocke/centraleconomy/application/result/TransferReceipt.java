package com.blocke.centraleconomy.application.result;

import com.blocke.centraleconomy.domain.money.Money;

import java.util.UUID;

/** Exact amounts and policy versions committed for one player transfer. */
public record TransferReceipt(
        UUID entryId,
        Money principal,
        Money senderDebit,
        Money recipientNet,
        Money fee,
        Money incomeTax,
        UUID feeRuleVersion,
        UUID incomeTaxRuleVersion) {}
