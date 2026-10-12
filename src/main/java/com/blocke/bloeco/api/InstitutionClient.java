package com.blocke.bloeco.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Capability-bound economy client returned to one registered plugin institution. */
public interface InstitutionClient {
    String clientId();
    Set<InstitutionCapability> capabilities();
    Map<String, InstitutionAccount> accounts();
    default Optional<InstitutionAccount> account(String purpose) {
        if (purpose == null) return Optional.empty();
        return Optional.ofNullable(accounts().get(purpose.trim().toLowerCase(java.util.Locale.ROOT)));
    }
    CompletionStage<EconomyResult<MoneyAmount>> walletBalance(UUID playerId);
    CompletionStage<EconomyResult<MoneyAmount>> balance(InstitutionAccount account);
    CompletionStage<EconomyResult<SettlementReceipt>> chargePlayer(
            UUID playerId, InstitutionAccount account, MoneyAmount amount,
            SettlementKind kind, OperationContext context);
    CompletionStage<EconomyResult<SettlementReceipt>> payPlayer(
            InstitutionAccount account, UUID playerId, MoneyAmount amount,
            SettlementKind kind, OperationContext context);
    CompletionStage<EconomyResult<SettlementReceipt>> refund(
            UUID originalOperationId, MoneyAmount amount, OperationContext context);
    CompletionStage<EconomyResult<ReservationReceipt>> reservePlayerFunds(
            UUID playerId, MoneyAmount amount, Instant expiresAt, OperationContext context);
    CompletionStage<EconomyResult<SettlementReceipt>> captureReservation(
            UUID reservationId, InstitutionAccount account, OperationContext context);
    CompletionStage<EconomyResult<ReservationReceipt>> releaseReservation(
            UUID reservationId, OperationContext context);
    CompletionStage<EconomyResult<Optional<SettlementReceipt>>> findOperation(String idempotencyKey);
    CompletionStage<EconomyResult<List<SettlementReceipt>>> recentTransactions(
            InstitutionAccount account, int limit);
}
