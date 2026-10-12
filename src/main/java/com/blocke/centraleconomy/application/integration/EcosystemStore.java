package com.blocke.centraleconomy.application.integration;

import com.blocke.bloeco.api.InstitutionCapability;
import com.blocke.bloeco.api.SettlementKind;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.money.Money;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persistence boundary for Bloeco-compatible institution settlement features. */
public interface EcosystemStore extends AutoCloseable {
    InstitutionRecord registerOrResume(InstitutionRegistration registration);
    Optional<InstitutionRecord> institution(String clientId);
    SettlementRecord commitSettlement(SettlementCommand command);
    SettlementRecord commitRefund(RefundCommand command);
    Optional<SettlementRecord> findOperation(String clientId, String idempotencyKey);
    List<SettlementRecord> recentTransactions(String clientId, String accountPurpose, int limit);
    long balance(AccountId accountId);
    @Override void close();

    record InstitutionRegistration(
            String clientId,
            String displayName,
            String pluginName,
            String pluginMainClass,
            String pluginVersion,
            int apiMajor,
            Set<InstitutionCapability> capabilities,
            Set<String> accountPurposes,
            Instant registeredAt) {
        public InstitutionRegistration {
            capabilities = Set.copyOf(capabilities);
            accountPurposes = Set.copyOf(accountPurposes);
        }
    }

    record SettlementCommand(
            UUID operationId, UUID journalId, String clientId, SettlementRecord.Type type,
            SettlementKind kind, UUID playerId, AccountId institutionAccountId,
            Money principal, Money tax, Money fee, Money institutionNet,
            UUID taxRuleVersion, String businessType, String businessReference,
            String displayMemo, String idempotencyKey, String fingerprint, Instant committedAt) {}

    record RefundCommand(
            UUID operationId, UUID journalId, String clientId, UUID originalOperationId,
            Money principal, String businessType, String businessReference, String displayMemo,
            String idempotencyKey, String fingerprint, Instant committedAt) {}
}
