package com.blocke.centraleconomy.storage.mysql;

import com.blocke.centraleconomy.domain.account.Account;
import com.blocke.centraleconomy.application.CentralBankService;
import com.blocke.centraleconomy.application.TaxRuleService;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.money.Money;
import java.sql.DriverManager;
import java.sql.Connection;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.blocke.centraleconomy.storage.JournalPageAssertions.assertStableJournalPages;

@Tag("mysql-integration")
class MySqlLedgerStoreIntegrationTest {
    private static final UUID TEST_PLAYER = UUID.fromString("00000000-0000-0000-0000-000000001201");

    @Test
    @EnabledIfEnvironmentVariable(named = "BLOECO_MYSQL_TEST_URL", matches = ".+")
    void journalCursorContinuesBeforeLastEntryWhenNewEntriesArrive() {
        try (var ledger = new MySqlLedgerStore(System.getenv("BLOECO_MYSQL_TEST_URL"),
                System.getenv("BLOECO_MYSQL_TEST_USER"), System.getenv("BLOECO_MYSQL_TEST_PASSWORD"), 2)) {
            assertStableJournalPages(ledger);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BLOECO_MYSQL_TEST_URL", matches = ".+")
    void integritySnapshotDoesNotObserveWritesCommittedBetweenReads() {
        try (var source = new BloecoDataSource(System.getenv("BLOECO_MYSQL_TEST_URL"),
                System.getenv("BLOECO_MYSQL_TEST_USER"), System.getenv("BLOECO_MYSQL_TEST_PASSWORD"), 2)) {
            var transactions = new MySqlTransactionManager(source.dataSource());
            try (var ledger = new MySqlLedgerStore(source, transactions)) {
                Account player = Account.player(UUID.randomUUID());
                transactions.readSnapshot(connection -> {
                    assertTrue(connection.isReadOnly());
                    assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation());
                    assertEquals(0, countAccount(connection, player.id()));
                    ledger.createAccount(player);
                    assertEquals(0, countAccount(connection, player.id()));
                    return null;
                });
                assertEquals(0L, ledger.balance(player.id()));
            }
        }
    }

    private static int countAccount(Connection connection, AccountId account) throws java.sql.SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM account_balances WHERE account_id = ?")) {
            statement.setString(1, account.value());
            try (var row = statement.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

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
            boolean emptyLedger = store.entryCount() == 0;
            long supplyBefore = store.monetaryTotals().netSupplyMinor();
            bank.bootstrapTreasury(Money.parse("1000000.00"));

            assertEquals(emptyLedger ? 100_000_000L : supplyBefore,
                    store.monetaryTotals().netSupplyMinor());
            assertTrue(store.verifyIntegrity().valid());
        }
    }
}
