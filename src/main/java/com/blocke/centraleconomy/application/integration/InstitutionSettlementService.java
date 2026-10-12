package com.blocke.centraleconomy.application.integration;

import com.blocke.bloeco.api.InstitutionCapability;
import com.blocke.bloeco.api.OperationContext;
import com.blocke.bloeco.api.SettlementKind;
import com.blocke.centraleconomy.application.TaxRuleService;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.ledger.LedgerException;
import com.blocke.centraleconomy.domain.money.Money;
import com.blocke.centraleconomy.domain.tax.TaxCategory;
import com.blocke.centraleconomy.domain.tax.TaxRule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Applies central policy before asking the authoritative store to settle atomically. */
public final class InstitutionSettlementService {
    private final EcosystemStore store;
    private final TaxRuleService taxes;
    private final Clock clock;

    public InstitutionSettlementService(EcosystemStore store, TaxRuleService taxes, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.taxes = Objects.requireNonNull(taxes, "taxes");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public SettlementRecord chargePlayer(String clientId, UUID playerId, String accountPurpose,
                                         Money principal, SettlementKind kind, OperationContext context) {
        requireKind(kind, SettlementKind.PURCHASE, SettlementKind.SERVICE_FEE);
        InstitutionRecord institution = authorized(clientId, InstitutionCapability.CHARGE_PLAYER);
        AccountId account = ownedAccount(institution, accountPurpose);
        requirePositive(principal);
        TaxRule rule = taxes.current(TaxCategory.PLUGIN_CONSUMPTION);
        Money tax = rule.charge(principal);
        String fingerprint = fingerprint("CHARGE", clientId, playerId, account, principal.minor(), kind,
                context.businessType(), context.businessReference(), context.displayMemo());
        return store.commitSettlement(new EcosystemStore.SettlementCommand(
                UUID.randomUUID(), UUID.randomUUID(), clientId, SettlementRecord.Type.CHARGE, kind,
                playerId, account, principal, tax, Money.ofMinor(0), principal, rule.versionId(),
                context.businessType(), context.businessReference(), context.displayMemo(),
                context.idempotencyKey(), fingerprint, clock.instant()));
    }

    public SettlementRecord payPlayer(String clientId, String accountPurpose, UUID playerId,
                                      Money principal, SettlementKind kind, OperationContext context) {
        requireKind(kind, SettlementKind.PAYOUT, SettlementKind.REWARD);
        InstitutionRecord institution = authorized(clientId, InstitutionCapability.PAY_PLAYER);
        AccountId account = ownedAccount(institution, accountPurpose);
        requirePositive(principal);
        String fingerprint = fingerprint("PAYOUT", clientId, playerId, account, principal.minor(), kind,
                context.businessType(), context.businessReference(), context.displayMemo());
        return store.commitSettlement(new EcosystemStore.SettlementCommand(
                UUID.randomUUID(), UUID.randomUUID(), clientId, SettlementRecord.Type.PAYOUT, kind,
                playerId, account, principal, Money.ofMinor(0), Money.ofMinor(0), principal, null,
                context.businessType(), context.businessReference(), context.displayMemo(),
                context.idempotencyKey(), fingerprint, clock.instant()));
    }

    public SettlementRecord refund(String clientId, UUID originalOperationId, Money principal,
                                   OperationContext context) {
        authorized(clientId, InstitutionCapability.REFUND);
        requirePositive(principal);
        String fingerprint = fingerprint("REFUND", clientId, originalOperationId, principal.minor(),
                context.businessType(), context.businessReference(), context.displayMemo());
        return store.commitRefund(new EcosystemStore.RefundCommand(
                UUID.randomUUID(), UUID.randomUUID(), clientId, originalOperationId, principal,
                context.businessType(), context.businessReference(), context.displayMemo(),
                context.idempotencyKey(), fingerprint, clock.instant()));
    }

    private InstitutionRecord authorized(String clientId, InstitutionCapability capability) {
        InstitutionRecord record = store.institution(clientId).orElseThrow(() ->
                new LedgerException(LedgerException.Code.UNAUTHORIZED, "institution is not registered"));
        if (record.status() != InstitutionRecord.Status.ACTIVE) throw new LedgerException(
                LedgerException.Code.UNAUTHORIZED, "institution is not active");
        if (!record.capabilities().contains(capability)) throw new LedgerException(
                LedgerException.Code.CAPABILITY_DENIED, "institution capability is not granted: " + capability);
        return record;
    }

    private static AccountId ownedAccount(InstitutionRecord institution, String purpose) {
        AccountId account = institution.accounts().get(Objects.requireNonNull(purpose, "accountPurpose")
                .trim().toLowerCase(java.util.Locale.ROOT));
        if (account == null) throw new LedgerException(LedgerException.Code.UNAUTHORIZED,
                "institution account is not owned by the caller");
        return account;
    }

    private static void requirePositive(Money amount) {
        if (Objects.requireNonNull(amount, "amount").minor() <= 0) throw new LedgerException(
                LedgerException.Code.INVALID_AMOUNT, "settlement amount must be positive");
    }

    private static void requireKind(SettlementKind actual, SettlementKind first, SettlementKind second) {
        if (actual != first && actual != second) throw new LedgerException(
                LedgerException.Code.POLICY_REJECTED, "settlement kind is not valid for this operation");
    }

    private static String fingerprint(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
