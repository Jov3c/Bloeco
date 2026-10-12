package com.blocke.centraleconomy.application.integration;

import com.blocke.bloeco.api.InstitutionCapability;
import com.blocke.bloeco.api.InstitutionDescriptor;
import com.blocke.bloeco.api.OperationContext;
import com.blocke.bloeco.api.SettlementKind;
import com.blocke.centraleconomy.application.CentralBankService;
import com.blocke.centraleconomy.application.TaxRuleService;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.ledger.LedgerException;
import com.blocke.centraleconomy.domain.money.Money;
import com.blocke.centraleconomy.storage.sqlite.SqliteEcosystemStore;
import com.blocke.centraleconomy.storage.sqlite.SqliteLedgerStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InstitutionSettlementServiceTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T09:00:00Z"), ZoneOffset.UTC);
    @TempDir Path temporaryDirectory;

    @Test
    void chargeAndRefundUseCentralTaxSnapshotWithoutChangingSupply() {
        try (Fixture fixture = fixture(Set.of(InstitutionCapability.CHARGE_PLAYER,
                InstitutionCapability.REFUND))) {
            SettlementRecord charge = fixture.service.chargePlayer("bloeco.shop", PLAYER, "sales",
                    Money.ofMinor(10_000), SettlementKind.PURCHASE, context("订单结算", "order-1"));

            assertEquals(500, charge.tax().minor());
            assertEquals(10_000, fixture.store.balance(AccountId.plugin("bloeco.shop", "sales")));
            assertEquals(9_500, fixture.store.balance(AccountId.player(PLAYER)));
            assertEquals(500, fixture.store.balance(AccountId.taxRevenue()));
            assertEquals(20_000, fixture.totalPositiveBalances());

            SettlementRecord partial = fixture.service.refund("bloeco.shop", charge.operationId(),
                    Money.ofMinor(4_000), context("部分退款", "refund-1"));
            SettlementRecord remainder = fixture.service.refund("bloeco.shop", charge.operationId(),
                    Money.ofMinor(6_000), context("剩余退款", "refund-2"));

            assertEquals(200, partial.tax().minor());
            assertEquals(300, remainder.tax().minor());
            assertEquals(20_000, fixture.store.balance(AccountId.player(PLAYER)));
            assertEquals(0, fixture.store.balance(AccountId.plugin("bloeco.shop", "sales")));
            assertThrows(LedgerException.class, () -> fixture.service.refund("bloeco.shop",
                    charge.operationId(), Money.ofMinor(1), context("超额退款", "refund-3")));
        }
    }

    @Test
    void retryReturnsSameReceiptButChangedPayloadConflicts() {
        try (Fixture fixture = fixture(Set.of(InstitutionCapability.CHARGE_PLAYER))) {
            SettlementRecord first = fixture.service.chargePlayer("bloeco.shop", PLAYER, "sales",
                    Money.ofMinor(1_000), SettlementKind.SERVICE_FEE, context("服务费", "same-key"));
            SettlementRecord replay = fixture.service.chargePlayer("bloeco.shop", PLAYER, "sales",
                    Money.ofMinor(1_000), SettlementKind.SERVICE_FEE, context("服务费", "same-key"));
            assertEquals(first.operationId(), replay.operationId());
            assertThrows(LedgerException.class, () -> fixture.service.chargePlayer("bloeco.shop", PLAYER,
                    "sales", Money.ofMinor(2_000), SettlementKind.SERVICE_FEE,
                    context("服务费", "same-key")));
        }
    }

    @Test
    void payoutRequiresCapabilityAndInstitutionFunds() {
        try (Fixture fixture = fixture(Set.of(InstitutionCapability.CHARGE_PLAYER))) {
            LedgerException denied = assertThrows(LedgerException.class,
                    () -> fixture.service.payPlayer("bloeco.shop", "sales", PLAYER,
                            Money.ofMinor(100), SettlementKind.REWARD, context("奖励", "reward-1")));
            assertEquals(LedgerException.Code.CAPABILITY_DENIED, denied.code());
        }
        try (Fixture fixture = fixture(Set.of(InstitutionCapability.PAY_PLAYER))) {
            LedgerException insufficient = assertThrows(LedgerException.class,
                    () -> fixture.service.payPlayer("bloeco.shop", "sales", PLAYER,
                            Money.ofMinor(100), SettlementKind.PAYOUT, context("提现", "payout-1")));
            assertEquals(LedgerException.Code.INSUFFICIENT_FUNDS, insufficient.code());
        }
    }

    @Test
    void concurrentDuplicateRefundCommitsOnlyOnce() throws Exception {
        try (Fixture fixture = fixture(Set.of(InstitutionCapability.CHARGE_PLAYER,
                InstitutionCapability.REFUND))) {
            SettlementRecord charge = fixture.service.chargePlayer("bloeco.shop", PLAYER, "sales",
                    Money.ofMinor(1_000), SettlementKind.PURCHASE, context("购买", "concurrent-charge"));
            Callable<SettlementRecord> refund = () -> fixture.service.refund("bloeco.shop",
                    charge.operationId(), Money.ofMinor(1_000), context("退款", "concurrent-refund"));
            try (var executor = Executors.newFixedThreadPool(2)) {
                var results = executor.invokeAll(java.util.List.of(refund, refund));
                assertEquals(results.get(0).get().operationId(), results.get(1).get().operationId());
            }
            assertEquals(20_000, fixture.store.balance(AccountId.player(PLAYER)));
            assertEquals(0, fixture.store.balance(AccountId.plugin("bloeco.shop", "sales")));
        }
    }

    private Fixture fixture(Set<InstitutionCapability> capabilities) {
        Path database = temporaryDirectory.resolve(UUID.randomUUID() + ".db");
        SqliteLedgerStore ledger = new SqliteLedgerStore(database);
        CentralBankService bank = new CentralBankService(ledger, CLOCK);
        bank.initializeCentralAccounts();
        bank.bootstrapTreasury(Money.ofMinor(20_000));
        bank.grantStarterFunds(PLAYER, Money.ofMinor(20_000));
        TaxRuleService taxes = new TaxRuleService(ledger, CLOCK);
        taxes.initializeDefaults();
        SqliteEcosystemStore store = new SqliteEcosystemStore(database);
        new InstitutionService(store, CLOCK).registerOrResume("Bloeco-Shop", "test.Shop", "1.0",
                new InstitutionDescriptor("bloeco.shop", "商店", capabilities, Set.of("sales")));
        return new Fixture(ledger, store, new InstitutionSettlementService(store, taxes, CLOCK));
    }

    private static OperationContext context(String memo, String key) {
        return new OperationContext("shop.order", "ORDER-1", memo, key);
    }

    private record Fixture(SqliteLedgerStore ledger, SqliteEcosystemStore store,
                           InstitutionSettlementService service) implements AutoCloseable {
        long totalPositiveBalances() {
            return store.balance(AccountId.treasury()) + store.balance(AccountId.player(PLAYER))
                    + store.balance(AccountId.taxRevenue())
                    + store.balance(AccountId.plugin("bloeco.shop", "sales"));
        }
        @Override public void close() { store.close(); ledger.close(); }
    }
}
