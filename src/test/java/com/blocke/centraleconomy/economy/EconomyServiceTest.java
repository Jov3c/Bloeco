package com.blocke.centraleconomy.economy;

import com.blocke.centraleconomy.ledger.AccountId;
import com.blocke.centraleconomy.ledger.SqliteLedgerRepository;
import com.blocke.centraleconomy.money.Money;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EconomyServiceTest {

    @TempDir
    Path temporaryDirectory;

    private SqliteLedgerRepository repository;
    private EconomyService service;
    private UUID player;
    private ProcurementItem wheatAt100Cents;

    @BeforeEach
    void setUp() {
        repository = new SqliteLedgerRepository(temporaryDirectory.resolve("economy.db"));
        service = new EconomyService(repository, 5);
        player = UUID.randomUUID();
        wheatAt100Cents = new ProcurementItem("minecraft:wheat", Money.ofCents(100), 64, true);
    }

    @AfterEach
    void tearDown() {
        repository.close();
    }

    @Test
    void procurementMovesGrossThenTaxAndLeavesTreasuryNetExpense() {
        service.issueToTreasury(Money.ofCents(10_000), "opening funds");

        var quote = service.quoteProcurement(player, wheatAt100Cents, 10);
        var result = service.settleProcurement(player, quote);

        assertEquals(1_000, result.gross().cents());
        assertEquals(50, result.tax().cents());
        assertEquals(950, result.playerNet().cents());
        assertEquals(9_050, service.treasuryBalance().cents());
        assertEquals(950, service.playerBalance(player).cents());
        assertEquals(3, repository.entryCount());
    }

    @Test
    void unaffordableProcurementDoesNotChangeAnyBalanceOrInsertEntries() {
        service.issueToTreasury(Money.ofCents(999), "limited funds");
        var quote = service.quoteProcurement(player, wheatAt100Cents, 10);

        assertThrows(IllegalStateException.class, () -> service.settleProcurement(player, quote));

        assertEquals(999, service.treasuryBalance().cents());
        assertEquals(0, service.playerBalance(player).cents());
        assertEquals(1, repository.entryCount());
    }

    @Test
    void procurementRejectsQuantityOutsideConfiguredLimitWithoutMutation() {
        assertThrows(IllegalArgumentException.class, () -> service.quoteProcurement(player, wheatAt100Cents, 0));
        assertThrows(IllegalArgumentException.class, () -> service.quoteProcurement(player, wheatAt100Cents, 65));

        assertEquals(0, service.treasuryBalance().cents());
        assertEquals(0, service.playerBalance(player).cents());
        assertEquals(0, repository.entryCount());
    }

    @Test
    void procurementRejectsDisabledItemsBeforeWritingTheLedger() {
        var disabledWheat = new ProcurementItem("minecraft:wheat", Money.ofCents(100), 64, false);

        assertThrows(IllegalArgumentException.class, () -> service.quoteProcurement(player, disabledWheat, 1));

        assertEquals(0, repository.entryCount());
    }

    @Test
    void procurementRejectsPriceMultiplicationOverflow() {
        var expensiveItem = new ProcurementItem("minecraft:nether_star", Money.ofCents(Long.MAX_VALUE), 2, true);

        assertThrows(IllegalArgumentException.class, () -> service.quoteProcurement(player, expensiveItem, 2));

        assertEquals(0, repository.entryCount());
    }

    @Test
    void procurementTaxRateMustBeAWholePercentFromZeroThroughOneHundred() {
        assertThrows(IllegalArgumentException.class, () -> new EconomyService(repository, -1));
        assertThrows(IllegalArgumentException.class, () -> new EconomyService(repository, 101));
    }

    @Test
    void issueAndBurnMoveFundsBetweenTheirSystemAccounts() {
        service.issueToTreasury(Money.ofCents(2_000), "issue");
        service.burnFromTreasury(Money.ofCents(500), "burn");

        assertEquals(1_500, service.treasuryBalance().cents());
        assertEquals(500, repository.balance(AccountId.burn()).cents());
        assertEquals(2, repository.entryCount());
    }
}
