package com.blocke.centraleconomy.ledger;

import com.blocke.centraleconomy.money.Money;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqliteLedgerRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    private SqliteLedgerRepository repository;
    private UUID player;

    @BeforeEach
    void setUp() {
        repository = new SqliteLedgerRepository(temporaryDirectory.resolve("economy.db"));
        player = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        repository.close();
    }

    @Test
    void transferPersistsBalancesAndOneLedgerEntry() {
        repository.credit(AccountId.treasury(), Money.ofCents(10_000), TransactionType.ISSUE, "seed");
        repository.transfer(AccountId.treasury(), AccountId.player(player), Money.ofCents(2_500), TransactionType.TREASURY_ALLOCATION, "approved allocation");

        assertEquals(7_500, repository.balance(AccountId.treasury()).cents());
        assertEquals(2_500, repository.balance(AccountId.player(player)).cents());
        assertEquals(2, repository.entryCount());
    }

    @Test
    void batchGrossAndTaxPostingsCommitTogether() {
        UUID seller = UUID.randomUUID();
        repository.credit(AccountId.treasury(), Money.ofCents(10_000), TransactionType.ISSUE, "seed");

        repository.transferBatch(List.of(
                new LedgerRepository.Posting(
                        AccountId.treasury(), AccountId.player(seller), Money.ofCents(2_500),
                        TransactionType.TREASURY_ALLOCATION, "approved allocation"),
                new LedgerRepository.Posting(
                        AccountId.player(seller), AccountId.treasury(), Money.ofCents(125),
                        TransactionType.PERSONAL_INCOME_TAX, "income tax")));

        assertEquals(7_625, repository.balance(AccountId.treasury()).cents());
        assertEquals(2_375, repository.balance(AccountId.player(seller)).cents());
        assertEquals(3, repository.entryCount());
    }

    @Test
    void insufficientAggregateDebitRollsTheEntireBatchBack() {
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        repository.credit(AccountId.treasury(), Money.ofCents(1_000), TransactionType.ISSUE, "seed");

        assertThrows(IllegalStateException.class, () -> repository.transferBatch(List.of(
                new LedgerRepository.Posting(
                        AccountId.treasury(), AccountId.player(firstPlayer), Money.ofCents(800),
                        TransactionType.TREASURY_ALLOCATION, "first allocation"),
                new LedgerRepository.Posting(
                        AccountId.treasury(), AccountId.player(secondPlayer), Money.ofCents(300),
                        TransactionType.TREASURY_ALLOCATION, "second allocation"))));

        assertEquals(1_000, repository.balance(AccountId.treasury()).cents());
        assertEquals(0, repository.balance(AccountId.player(firstPlayer)).cents());
        assertEquals(0, repository.balance(AccountId.player(secondPlayer)).cents());
        assertEquals(1, repository.entryCount());
    }

    @Test
    void rejectsNonIssueDebitFromIssuance() {
        assertThrows(IllegalArgumentException.class, () -> repository.transfer(
                AccountId.issuance(),
                AccountId.player(UUID.randomUUID()),
                Money.ofCents(100),
                TransactionType.TREASURY_ALLOCATION,
                "invalid issuance"));

        assertEquals(0, repository.entryCount());
    }
}
