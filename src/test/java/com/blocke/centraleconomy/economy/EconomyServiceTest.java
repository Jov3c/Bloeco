package com.blocke.centraleconomy.economy;

import com.blocke.centraleconomy.ledger.AccountId;
import com.blocke.centraleconomy.ledger.SqliteLedgerRepository;
import com.blocke.centraleconomy.ledger.TransactionType;
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

    @Test
    void blockStockReserveFundingMovesExistingTreasuryFundsWithoutIssuingCurrency() {
        UUID reserveTreasury = UUID.fromString("00000000-0000-0000-0000-000000000098");
        service.issueToTreasury(Money.ofCents(115_000_000), "approved opening supply");

        service.fundBlockStockReserve(reserveTreasury, Money.ofCents(115_000_000), "bluechip market reserve");

        assertEquals(0, service.treasuryBalance().cents());
        assertEquals(115_000_000, service.playerBalance(reserveTreasury).cents());
        assertEquals(115_000_000, repository.balance(AccountId.issuance()).cents());
        assertEquals(2, repository.entryCount());
    }

    @Test
    void blockStockReserveFundingRejectsInsufficientTreasuryWithoutMutation() {
        UUID reserveTreasury = UUID.fromString("00000000-0000-0000-0000-000000000098");
        service.issueToTreasury(Money.ofCents(100), "limited supply");

        assertThrows(IllegalStateException.class, () ->
                service.fundBlockStockReserve(reserveTreasury, Money.ofCents(101), "bluechip market reserve"));

        assertEquals(100, service.treasuryBalance().cents());
        assertEquals(0, service.playerBalance(reserveTreasury).cents());
        assertEquals(1, repository.entryCount());
    }

    @Test
    void playerTransferChargesSenderFeeAndRecipientIncomeTaxInOneBatch() {
        UUID recipient = UUID.randomUUID();
        repository.credit(AccountId.player(player), Money.ofCents(100_000), TransactionType.ISSUE, "sender funds");

        service.transferPlayerFunds(player, recipient, Money.ofCents(10_000), "payment for crops");

        assertEquals(89_900, service.playerBalance(player).cents());
        assertEquals(9_500, service.playerBalance(recipient).cents());
        assertEquals(600, service.treasuryBalance().cents());
        assertEquals(4, repository.entryCount());
    }

    @Test
    void playerTransferRejectsAmountPlusFeeWhenSenderHasInsufficientFunds() {
        UUID recipient = UUID.randomUUID();
        repository.credit(AccountId.player(player), Money.ofCents(10_099), TransactionType.ISSUE, "sender funds");

        assertThrows(IllegalStateException.class, () ->
                service.transferPlayerFunds(player, recipient, Money.ofCents(10_000), "payment for crops"));

        assertEquals(10_099, service.playerBalance(player).cents());
        assertEquals(0, service.playerBalance(recipient).cents());
        assertEquals(0, service.treasuryBalance().cents());
        assertEquals(1, repository.entryCount());
    }

}
