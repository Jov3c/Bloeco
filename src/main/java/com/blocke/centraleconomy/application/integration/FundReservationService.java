package com.blocke.centraleconomy.application.integration;

import com.blocke.bloeco.api.InstitutionCapability;
import com.blocke.bloeco.api.OperationContext;
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
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Reserves player purchasing power and atomically captures or releases it. */
public final class FundReservationService {
    private final EcosystemStore store;
    private final TaxRuleService taxes;
    private final Clock clock;

    public FundReservationService(EcosystemStore store, TaxRuleService taxes, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.taxes = Objects.requireNonNull(taxes, "taxes");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ReservationRecord reserve(String clientId, UUID playerId, Money principal, Instant expiresAt,
                                     OperationContext context) {
        authorized(clientId);
        if (principal == null || principal.minor() <= 0) throw new LedgerException(
                LedgerException.Code.INVALID_AMOUNT, "reservation amount must be positive");
        TaxRule rule = taxes.current(TaxCategory.PLUGIN_CONSUMPTION);
        Money tax = rule.charge(principal);
        return store.reserve(new EcosystemStore.ReservationCommand(UUID.randomUUID(), clientId, playerId,
                principal, tax, rule.versionId(), expiresAt, clock.instant(), context.idempotencyKey(),
                fingerprint("RESERVE", clientId, playerId, principal.minor(), expiresAt,
                        context.businessType(), context.businessReference(), context.displayMemo())));
    }

    public SettlementRecord capture(String clientId, UUID reservationId, String accountPurpose,
                                    OperationContext context) {
        InstitutionRecord institution = authorized(clientId);
        AccountId account = institution.accounts().get(Objects.requireNonNull(accountPurpose)
                .trim().toLowerCase(Locale.ROOT));
        if (account == null) throw new LedgerException(LedgerException.Code.UNAUTHORIZED,
                "institution account is not owned by the caller");
        return store.capture(new EcosystemStore.CaptureCommand(UUID.randomUUID(), UUID.randomUUID(), clientId,
                reservationId, account, context.businessType(), context.businessReference(), context.displayMemo(),
                context.idempotencyKey(), fingerprint("CAPTURE", clientId, reservationId, account,
                        context.businessType(), context.businessReference(), context.displayMemo()), clock.instant()));
    }

    public ReservationRecord release(String clientId, UUID reservationId, OperationContext context) {
        authorized(clientId);
        return store.release(new EcosystemStore.ReservationTransition(clientId, reservationId,
                context.idempotencyKey(), fingerprint("RELEASE", clientId, reservationId,
                context.businessType(), context.businessReference(), context.displayMemo()), clock.instant()));
    }

    public ReservationRecord reservation(String clientId, UUID reservationId) {
        authorized(clientId);
        return store.reservation(clientId, reservationId, clock.instant()).orElseThrow(() ->
                new LedgerException(LedgerException.Code.INVALID_JOURNAL, "reservation does not exist"));
    }

    private InstitutionRecord authorized(String clientId) {
        InstitutionRecord institution = store.institution(clientId).orElseThrow(() ->
                new LedgerException(LedgerException.Code.UNAUTHORIZED, "institution is not registered"));
        if (institution.status() != InstitutionRecord.Status.ACTIVE
                || !institution.capabilities().contains(InstitutionCapability.RESERVE_PLAYER_FUNDS)) {
            throw new LedgerException(LedgerException.Code.CAPABILITY_DENIED,
                    "reservation capability is not granted");
        }
        return institution;
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
            throw new IllegalStateException(exception);
        }
    }
}
