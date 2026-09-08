package com.blocke.centraleconomy.application;

import com.blocke.centraleconomy.application.command.PlayerPayment;
import com.blocke.centraleconomy.application.result.TransferReceipt;
import com.blocke.centraleconomy.domain.money.Money;

import java.util.Objects;
import java.util.UUID;

/** Persistent settlement facts required to replay a payment independently of later tax changes. */
public record PlayerSettlement(
        UUID entryId,
        UUID senderId,
        UUID recipientId,
        Money principal,
        Money senderDebit,
        Money recipientNet,
        Money fee,
        Money incomeTax,
        UUID feeRuleVersion,
        UUID incomeTaxRuleVersion,
        String memo) {

    public PlayerSettlement {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(senderDebit, "senderDebit");
        Objects.requireNonNull(recipientNet, "recipientNet");
        Objects.requireNonNull(fee, "fee");
        Objects.requireNonNull(incomeTax, "incomeTax");
        Objects.requireNonNull(feeRuleVersion, "feeRuleVersion");
        Objects.requireNonNull(incomeTaxRuleVersion, "incomeTaxRuleVersion");
        Objects.requireNonNull(memo, "memo");
    }

    public boolean sameCommand(PlayerPayment command) {
        return senderId.equals(command.senderId()) && recipientId.equals(command.recipientId())
                && principal.equals(command.principal()) && memo.equals(command.memo());
    }

    public TransferReceipt receipt() {
        return new TransferReceipt(entryId, principal, senderDebit, recipientNet, fee, incomeTax,
                feeRuleVersion, incomeTaxRuleVersion);
    }
}
