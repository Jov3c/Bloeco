package com.blocke.centraleconomy.storage.mysql;

import com.blocke.centraleconomy.domain.account.Account;
import com.blocke.centraleconomy.application.CentralBankService;
import com.blocke.centraleconomy.application.TaxRuleService;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.money.Money;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlLedgerStoreIntegrationTest {
    private static final UUID TEST_PLAYER = UUID.fromString("00000000-0000-0000-0000-000000001201");

    @Test
    @EnabledIfEnvironmentVariable(named = "BLOECO_MYSQL_TEST_URL", matches = ".+")
    void accountCreationMatchesTheVersionTwoMysqlSchema() throws Exception {
        String url = System.getenv("BLOECO_MYSQL_TEST_URL");
        String username = System.getenv("BLOECO_MYSQL_TEST_USER");
        String password = System.getenv("BLOECO_MYSQL_TEST_PASSWORD");
        Account account = Account.player(TEST_PLAYER);

        try (MySqlLedgerStore store = new MySqlLedgerStore(url, username, password, 2)) {
            store.createAccount(account);
            assertEquals(0L, store.balance(account.id()));
        } finally {
            try (var connection = DriverManager.getConnection(url, username, password);
                 var balances = connection.prepareStatement(
                         "DELETE FROM account_balances WHERE account_id = ?");
                 var accounts = connection.prepareStatement(
                         "DELETE FROM accounts WHERE account_id = ?")) {
                balances.setString(1, account.id().value());
                balances.executeUpdate();
                accounts.setString(1, account.id().value());
                accounts.executeUpdate();
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BLOECO_MYSQL_TEST_URL", matches = ".+")
    void freshMysqlSchemaSupportsCompleteCentralBankBootstrap() {
        String url = System.getenv("BLOECO_MYSQL_TEST_URL");
        String username = System.getenv("BLOECO_MYSQL_TEST_USER");
        String password = System.getenv("BLOECO_MYSQL_TEST_PASSWORD");

        try (MySqlLedgerStore store = new MySqlLedgerStore(url, username, password, 2)) {
            CentralBankService bank = new CentralBankService(store, Clock.systemUTC());
            bank.initializeCentralAccounts();
            new TaxRuleService(store, Clock.systemUTC()).initializeDefaults();
            bank.bootstrapTreasury(Money.parse("1000000.00"));

            assertEquals(100_000_000L, store.balance(AccountId.treasury()));
            assertTrue(store.verifyIntegrity().valid());
        }
    }
}
