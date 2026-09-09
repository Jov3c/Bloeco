package com.blocke.centraleconomy.storage.sqlite;

import com.blocke.centraleconomy.application.AsyncEconomyFacade;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.banking.BankingPolicy;
import com.blocke.centraleconomy.domain.money.Money;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SqliteBankingStoreTest {
    @TempDir Path temporaryDirectory;

    @Test
    void depositLoanAndRepaymentMoveExistingMoneyWithoutChangingSupply() {
        Path database = temporaryDirectory.resolve("economy.db");
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000001301");
        Clock clock = Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC);
        try (AsyncEconomyFacade economy = AsyncEconomyFacade.sqlite(database, clock, Money.parse("1000000.00"))) {
            economy.readyStage().toCompletableFuture().join();
            economy.adjustPlayerBalance(player, Money.parse("100.00"), "test", "测试资金", "player-fund")
                    .toCompletableFuture().join();
        }

        BankingPolicy policy = new BankingPolicy(100, 320, 2_000, Money.parse("10000.00"), true, 7);
        try (SqliteBankingStore bank = new SqliteBankingStore(database, clock)) {
            bank.initialize(Money.parse("250000.00"), policy);
            var deposit = bank.deposit(player, Money.parse("50.00"), "deposit-1");
            var replay = bank.deposit(player, Money.parse("50.00"), "deposit-1");
            assertEquals(deposit.journalId(), replay.journalId());

            var loan = bank.borrow(player, Money.parse("100.00"), "loan-1");
            var loanReplay = bank.borrow(player, Money.parse("100.00"), "loan-1");
            assertEquals(loan.loanId(), loanReplay.loanId());
            assertEquals(Money.parse("100.06"), loan.totalDue());
            bank.repay(player, loan.loanId(), Money.parse("100.06"), "repay-1");

            var playerView = bank.playerSnapshot(player);
            assertEquals(Money.parse("49.94"), playerView.wallet());
            assertEquals(Money.parse("50.00"), playerView.deposit());
            assertEquals(Money.ofMinor(0), playerView.loanDebt());
            assertFalse(playerView.hasOverdueLoan());

            var bankView = bank.bankSnapshot();
            assertEquals(Money.parse("250050.06"), bankView.cash());
            assertEquals(Money.parse("50.00"), bankView.depositLiabilities());
            assertEquals(Money.ofMinor(0), bankView.loanAssets());
        }

        try (SqliteLedgerStore ledger = new SqliteLedgerStore(database)) {
            assertEquals(100_000_000L, ledger.monetaryTotals().netSupplyMinor());
            assertEquals(74_990_000L, ledger.balance(AccountId.treasury()));
            assertEquals(true, ledger.verifyIntegrity().valid());
        }
    }

    @Test
    void depositInterestAccruesAsBankLiabilityAndCanBeWithdrawnFromRealCash() {
        Path database = temporaryDirectory.resolve("interest.db");
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000001302");
        Instant openedAt = Instant.parse("2026-01-01T00:00:00Z");
        Clock openingClock = Clock.fixed(openedAt, ZoneOffset.UTC);
        try (AsyncEconomyFacade economy = AsyncEconomyFacade.sqlite(
                database, openingClock, Money.parse("1000000"))) {
            economy.readyStage().toCompletableFuture().join();
            economy.adjustPlayerBalance(player, Money.parse("20000"), "test", "利息测试资金", "interest-fund")
                    .toCompletableFuture().join();
        }
        BankingPolicy policy = new BankingPolicy(100, 320, 2000, Money.parse("10000"), true, 7);
        try (SqliteBankingStore bank = new SqliteBankingStore(database, openingClock)) {
            bank.initialize(Money.parse("250000"), policy);
            bank.deposit(player, Money.parse("10000"), "interest-deposit");
        }

        Clock oneYearLater = Clock.fixed(openedAt.plusSeconds(365L * 86_400L), ZoneOffset.UTC);
        try (SqliteBankingStore bank = new SqliteBankingStore(database, oneYearLater)) {
            assertEquals(Money.parse("10100"), bank.playerSnapshot(player).deposit());
            bank.withdraw(player, Money.parse("10100"), "interest-withdraw");
            assertEquals(Money.ofMinor(0), bank.playerSnapshot(player).deposit());
        }
        try (SqliteLedgerStore ledger = new SqliteLedgerStore(database)) {
            assertEquals(100_000_000L, ledger.monetaryTotals().netSupplyMinor());
            assertEquals(true, ledger.verifyIntegrity().valid());
        }
    }
}
