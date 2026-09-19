package com.blocke.centraleconomy.storage.mysql;

import com.blocke.centraleconomy.application.CentralBankService;
import com.blocke.centraleconomy.domain.banking.BankingPolicy;
import com.blocke.centraleconomy.domain.money.Money;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlBankingStoreIntegrationTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "BLOECO_MYSQL_TEST_URL", matches = ".+")
    void mysqlCommitsConservedBankingOperationsAndOutboxEvents() {
        String url = System.getenv("BLOECO_MYSQL_TEST_URL");
        String username = System.getenv("BLOECO_MYSQL_TEST_USER");
        String password = System.getenv("BLOECO_MYSQL_TEST_PASSWORD");
        Clock clock = Clock.systemUTC();
        UUID player = UUID.randomUUID();
        UUID allBalancePlayer = UUID.randomUUID();
        String runKey = UUID.randomUUID().toString();
        long supplyBefore;
        try (MySqlLedgerStore ledger = new MySqlLedgerStore(url, username, password, 4)) {
            CentralBankService central = new CentralBankService(ledger, clock);
            central.initializeCentralAccounts();
            central.bootstrapTreasury(Money.parse("1000000"));
            supplyBefore = ledger.monetaryTotals().netSupplyMinor();
            central.adjustPlayerBalance(player, Money.parse("1000"), "mysql-bank-test",
                    "MySQL 银行测试资金", "mysql-bank-fund:" + runKey);
            central.adjustPlayerBalance(allBalancePlayer, Money.parse("123.45"), "mysql-bank-test",
                    "MySQL 银行全部存取测试资金", "mysql-bank-all-fund:" + runKey);
        }
        BankingPolicy policy = new BankingPolicy(100, 320, 2000, Money.parse("10000"), true, 7);
        try (MySqlBankingStore bank = new MySqlBankingStore(url, username, password, 4, clock)) {
            bank.initialize(Money.parse("250000"), policy);
            bank.deposit(player, Money.parse("500"), "mysql-bank-deposit:" + runKey);
            var loan = bank.borrow(player, Money.parse("100"), "mysql-bank-loan:" + runKey);
            bank.repay(player, loan.loanId(), Money.parse("100.06"), "mysql-bank-repay:" + runKey);
            assertEquals(Money.parse("500"), bank.playerSnapshot(player).deposit());
            assertEquals(Money.ofMinor(0), bank.playerSnapshot(player).loanDebt());
            bank.depositAll(allBalancePlayer, "mysql-bank-deposit-all:" + runKey);
            assertEquals(Money.ofMinor(0), bank.playerSnapshot(allBalancePlayer).wallet());
            assertEquals(Money.parse("123.45"), bank.playerSnapshot(allBalancePlayer).deposit());
            bank.withdrawAll(allBalancePlayer, "mysql-bank-withdraw-all:" + runKey);
            assertEquals(Money.parse("123.45"), bank.playerSnapshot(allBalancePlayer).wallet());
            assertEquals(Money.ofMinor(0), bank.playerSnapshot(allBalancePlayer).deposit());
        }
        try (MySqlLedgerStore ledger = new MySqlLedgerStore(url, username, password, 4)) {
            assertEquals(supplyBefore, ledger.monetaryTotals().netSupplyMinor());
            assertTrue(ledger.verifyIntegrity().valid());
        }
    }
}
