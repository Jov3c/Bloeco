package com.blocke.centraleconomy.application.integration;

import com.blocke.bloeco.api.InstitutionCapability;
import com.blocke.bloeco.api.InstitutionDescriptor;
import com.blocke.bloeco.api.OperationContext;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FundReservationServiceTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000066");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T10:00:00Z"), ZoneOffset.UTC);
    @TempDir Path temporaryDirectory;

    @Test
    void reservationReducesAvailableAndCaptureMovesPrincipalAndTaxOnce() {
        try (Fixture fixture = fixture()) {
            ReservationRecord reserved = fixture.service.reserve("bloeco.auction", PLAYER,
                    Money.ofMinor(10_000), CLOCK.instant().plusSeconds(60), context("竞拍冻结", "reserve-1"));
            assertEquals(10_500, fixture.store.reservedBalance(PLAYER));
            assertEquals(9_500, fixture.store.availableBalance(PLAYER));
            assertEquals(reserved.reservationId(), fixture.service.reserve("bloeco.auction", PLAYER,
                    Money.ofMinor(10_000), CLOCK.instant().plusSeconds(60),
                    context("竞拍冻结", "reserve-1")).reservationId());

            SettlementRecord captured = fixture.service.capture("bloeco.auction", reserved.reservationId(),
                    "escrow", context("竞拍成交", "capture-1"));
            assertEquals(500, captured.tax().minor());
            assertEquals(0, fixture.store.reservedBalance(PLAYER));
            assertEquals(9_500, fixture.store.balance(AccountId.player(PLAYER)));
            assertEquals(10_000, fixture.store.balance(AccountId.plugin("bloeco.auction", "escrow")));
            assertThrows(LedgerException.class, () -> fixture.service.capture("bloeco.auction",
                    reserved.reservationId(), "escrow", context("重复成交", "capture-2")));
        }
    }

    @Test
    void expirationAndReleaseReturnAvailabilityWithoutMovingMoney() {
        try (Fixture fixture = fixture()) {
            ReservationRecord active = fixture.service.reserve("bloeco.auction", PLAYER,
                    Money.ofMinor(1_000), CLOCK.instant().plusSeconds(60), context("冻结", "reserve-2"));
            ReservationRecord released = fixture.service.release("bloeco.auction", active.reservationId(),
                    context("取消", "release-1"));
            assertEquals(ReservationRecord.Status.RELEASED, released.status());
            assertEquals(released.status(), fixture.service.release("bloeco.auction", active.reservationId(),
                    context("取消", "release-1")).status());

            ReservationRecord expired = fixture.service.reserve("bloeco.auction", PLAYER,
                    Money.ofMinor(1_000), CLOCK.instant().minusSeconds(1), context("过期冻结", "reserve-3"));
            assertEquals(ReservationRecord.Status.EXPIRED,
                    fixture.service.reservation("bloeco.auction", expired.reservationId()).status());
            assertEquals(20_000, fixture.store.availableBalance(PLAYER));
        }
    }

    @Test
    void twoReservationsCannotExceedOneWallet() {
        try (Fixture fixture = fixture()) {
            fixture.service.reserve("bloeco.auction", PLAYER, Money.ofMinor(15_000),
                    CLOCK.instant().plusSeconds(60), context("第一笔", "reserve-a"));
            assertThrows(LedgerException.class, () -> fixture.service.reserve("bloeco.auction", PLAYER,
                    Money.ofMinor(5_000), CLOCK.instant().plusSeconds(60), context("第二笔", "reserve-b")));
        }
    }

    private Fixture fixture() {
        Path database = temporaryDirectory.resolve(UUID.randomUUID() + ".db");
        SqliteLedgerStore ledger = new SqliteLedgerStore(database);
        CentralBankService bank = new CentralBankService(ledger, CLOCK);
        bank.initializeCentralAccounts();
        bank.bootstrapTreasury(Money.ofMinor(20_000));
        bank.grantStarterFunds(PLAYER, Money.ofMinor(20_000));
        TaxRuleService taxes = new TaxRuleService(ledger, CLOCK);
        taxes.initializeDefaults();
        SqliteEcosystemStore store = new SqliteEcosystemStore(database);
        new InstitutionService(store, CLOCK).registerOrResume("Bloeco-Auction", "test.Auction", "1.0",
                new InstitutionDescriptor("bloeco.auction", "拍卖",
                        Set.of(InstitutionCapability.RESERVE_PLAYER_FUNDS), Set.of("escrow")));
        return new Fixture(ledger, store, new FundReservationService(store, taxes, CLOCK));
    }

    private static OperationContext context(String memo, String key) {
        return new OperationContext("auction.bid", "BID-1", memo, key);
    }

    private record Fixture(SqliteLedgerStore ledger, SqliteEcosystemStore store,
                           FundReservationService service) implements AutoCloseable {
        @Override public void close() { store.close(); ledger.close(); }
    }
}
