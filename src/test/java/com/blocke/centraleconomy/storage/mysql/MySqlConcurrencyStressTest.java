package com.blocke.centraleconomy.storage.mysql;

import com.blocke.centraleconomy.application.CentralBankService;
import com.blocke.centraleconomy.domain.account.Account;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.ledger.JournalEntry;
import com.blocke.centraleconomy.domain.ledger.JournalType;
import com.blocke.centraleconomy.domain.ledger.Posting;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlConcurrencyStressTest {
    private static final String CLIENT_ID = "bloeco-stress";
    private static final int WORKERS = 12;
    private static final int TRANSFERS_PER_WORKER = 300;
    private static final long INITIAL_BALANCE_MINOR = 100_000L;

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    @EnabledIfEnvironmentVariable(named = "BLOECO_MYSQL_TEST_URL", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "BLOECO_MYSQL_STRESS", matches = "true")
    void concurrentConnectionsPreserveEveryCentAndEveryBalancedJournal() throws Exception {
        String url = System.getenv("BLOECO_MYSQL_TEST_URL");
        String username = System.getenv("BLOECO_MYSQL_TEST_USER");
        String password = System.getenv("BLOECO_MYSQL_TEST_PASSWORD");
        List<Account> accounts = stressAccounts();
        long treasuryBefore = 0L;

        try {
            try (MySqlLedgerStore setup = new MySqlLedgerStore(url, username, password, 2)) {
                CentralBankService bank = new CentralBankService(setup, Clock.systemUTC());
                bank.initializeCentralAccounts();
                treasuryBefore = setup.balance(AccountId.treasury());
                accounts.forEach(setup::createAccount);
                List<Posting> funding = new ArrayList<>();
                funding.add(new Posting(AccountId.treasury(), -INITIAL_BALANCE_MINOR * WORKERS));
                accounts.forEach(account -> funding.add(new Posting(account.id(), INITIAL_BALANCE_MINOR)));
                setup.commit(JournalEntry.create(UUID.randomUUID(), JournalType.TREASURY_ALLOCATION,
                        "MySQL 并发压测准备金", CLIENT_ID, "funding-" + UUID.randomUUID(),
                        Instant.now(), funding));
            }

            CountDownLatch ready = new CountDownLatch(WORKERS);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            try (var executor = Executors.newFixedThreadPool(WORKERS)) {
                for (int worker = 0; worker < WORKERS; worker++) {
                    int workerId = worker;
                    futures.add(executor.submit(() -> {
                        try (MySqlLedgerStore store = new MySqlLedgerStore(url, username, password, 2)) {
                            ready.countDown();
                            assertTrue(start.await(30, TimeUnit.SECONDS), "all workers must start together");
                            AccountId source = accounts.get(workerId).id();
                            AccountId destination = accounts.get((workerId + 1) % WORKERS).id();
                            for (int operation = 0; operation < TRANSFERS_PER_WORKER; operation++) {
                                store.commit(JournalEntry.create(UUID.randomUUID(), JournalType.PLAYER_TRANSFER,
                                        "MySQL 并发压测转账", CLIENT_ID,
                                        "worker-" + workerId + "-operation-" + operation,
                                        Instant.now(), List.of(
                                                new Posting(source, -1L),
                                                new Posting(destination, 1L))));
                            }
                            return TRANSFERS_PER_WORKER;
                        }
                    }));
                }

                assertTrue(ready.await(30, TimeUnit.SECONDS), "all workers must acquire a MySQL connection");
                long startedAt = System.nanoTime();
                start.countDown();
                int completed = 0;
                for (Future<Integer> future : futures) completed += future.get(150, TimeUnit.SECONDS);
                Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
                double throughput = completed / (elapsed.toNanos() / 1_000_000_000.0);
                System.out.printf("BLOECO_STRESS workers=%d transfers=%d elapsed_ms=%d tx_per_second=%.2f%n",
                        WORKERS, completed, elapsed.toMillis(), throughput);
                assertEquals(WORKERS * TRANSFERS_PER_WORKER, completed);
            }

            try (MySqlLedgerStore verifier = new MySqlLedgerStore(url, username, password, 2)) {
                long total = 0L;
                for (Account account : accounts) {
                    long balance = verifier.balance(account.id());
                    assertEquals(INITIAL_BALANCE_MINOR, balance,
                            "the balanced transfer ring must return each wallet to its starting balance");
                    total = Math.addExact(total, balance);
                }
                assertEquals(INITIAL_BALANCE_MINOR * WORKERS, total);
                assertTrue(verifier.verifyIntegrity().valid(), "materialized balances must match all postings");
            }
        } finally {
            cleanup(url, username, password, accounts, treasuryBefore);
        }
    }

    private static List<Account> stressAccounts() {
        List<Account> accounts = new ArrayList<>();
        for (int index = 0; index < WORKERS; index++) {
            accounts.add(Account.player(UUID.fromString(
                    "00000000-0000-0000-0000-" + String.format("%012d", 900_000 + index))));
        }
        return List.copyOf(accounts);
    }

    private static void cleanup(
            String url, String username, String password, List<Account> accounts, long treasuryBefore) throws Exception {
        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.setAutoCommit(false);
            try (var outbox = connection.prepareStatement(
                         "DELETE FROM outbox_events WHERE aggregate_id IN "
                                 + "(SELECT entry_id FROM journal_entries WHERE client_id = ?)");
                 var idempotency = connection.prepareStatement(
                         "DELETE FROM idempotency_records WHERE client_id = ?");
                 var postings = connection.prepareStatement(
                         "DELETE FROM postings WHERE entry_id IN "
                                 + "(SELECT entry_id FROM journal_entries WHERE client_id = ?)");
                 var journals = connection.prepareStatement(
                         "DELETE FROM journal_entries WHERE client_id = ?");
                 var balance = connection.prepareStatement(
                         "DELETE FROM account_balances WHERE account_id = ?");
                 var account = connection.prepareStatement(
                         "DELETE FROM accounts WHERE account_id = ?");
                 var treasury = connection.prepareStatement(
                         "UPDATE account_balances SET balance_minor = ? WHERE account_id = ?")) {
                for (var statement : List.of(outbox, idempotency, postings, journals)) {
                    statement.setString(1, CLIENT_ID);
                    statement.executeUpdate();
                }
                for (Account stressAccount : accounts) {
                    balance.setString(1, stressAccount.id().value());
                    balance.executeUpdate();
                    account.setString(1, stressAccount.id().value());
                    account.executeUpdate();
                }
                treasury.setLong(1, treasuryBefore);
                treasury.setString(2, AccountId.treasury().value());
                treasury.executeUpdate();
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }
}
