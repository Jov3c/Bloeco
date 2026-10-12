package com.blocke.centraleconomy.application.integration;

import com.blocke.bloeco.api.SettlementKind;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.money.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Authoritative persisted result of one ecosystem settlement operation. */
public record SettlementRecord(
        UUID operationId, UUID journalId, String clientId, Type type, SettlementKind kind,
        UUID playerId, AccountId institutionAccountId, Money principal, Money tax, Money fee,
        Money institutionNet, String businessType, String businessReference, String displayMemo,
        String idempotencyKey, String fingerprint, Instant committedAt, UUID originalOperationId,
        UUID taxRuleVersion) {
    public SettlementRecord {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(journalId, "journalId");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(institutionAccountId, "institutionAccountId");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(tax, "tax");
        Objects.requireNonNull(fee, "fee");
        Objects.requireNonNull(institutionNet, "institutionNet");
        Objects.requireNonNull(businessType, "businessType");
        Objects.requireNonNull(businessReference, "businessReference");
        Objects.requireNonNull(displayMemo, "displayMemo");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(committedAt, "committedAt");
    }

    public enum Type { CHARGE, PAYOUT, REFUND }
}
