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
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000001399");
        try (MySqlLedgerStore ledger = new MySqlLedgerStore(url, username, password, 4)) {
            CentralBankService central = new CentralBankService(ledger, clock);
            central.initializeCentralAccounts();
            central.bootstrapTreasury(Money.parse("1000000"));
            central.adjustPlayerBalance(player, Money.parse("1000"), "mysql-bank-test",
                    "MySQL 银行测试资金", "mysql-bank-fund");
        }
        BankingPolicy policy = new BankingPolicy(100, 500, 2000, Money.parse("10000"), true, 7);
        try (MySqlBankingStore bank = new MySqlBankingStore(url, username, password, 4, clock)) {
            bank.initialize(Money.parse("250000"), policy);
            bank.deposit(player, Money.parse("500"), "mysql-bank-deposit");
            var loan = bank.borrow(player, Money.parse("100"), "mysql-bank-loan");
            bank.repay(player, loan.loanId(), Money.parse("105"), "mysql-bank-repay");
            assertEquals(Money.parse("500"), bank.playerSnapshot(player).deposit());
            assertEquals(Money.ofMinor(0), bank.playerSnapshot(player).loanDebt());
        }
        try (MySqlLedgerStore ledger = new MySqlLedgerStore(url, username, password, 4)) {
            assertEquals(100_000_000L, ledger.monetaryTotals().netSupplyMinor());
            assertTrue(ledger.verifyIntegrity().valid());
        }
    }
}
