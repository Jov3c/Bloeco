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
    @TempDir Path temporaryDirectory;
    private SqliteLedgerRepository repository;
    private EconomyService service;
    private UUID player;

    @BeforeEach
    void setUp() {
        repository = new SqliteLedgerRepository(temporaryDirectory.resolve("economy.db"));
        service = new EconomyService(repository, new TaxPolicy(1, 5));
        player = UUID.randomUUID();
    }
    @AfterEach void tearDown() { repository.close(); }

    @Test
    void issueAndBurnMoveFundsOnlyBetweenSystemAccounts() {
        service.issueToTreasury(Money.ofCents(2_000), "approved issue");
        service.burnFromTreasury(Money.ofCents(500), "approved burn");
        assertEquals(1_500, service.treasuryBalance().cents());
        assertEquals(500, repository.balance(AccountId.burn()).cents());
    }

    @Test
    void paymentCollectsFeeAndIncomeTaxIntoTreasuryAtomically() {
        UUID recipient = UUID.randomUUID();
        repository.credit(AccountId.player(player), Money.ofCents(10_100), TransactionType.ISSUE, "opening balance");
        service.transferPlayerFunds(player, recipient, Money.ofCents(10_000), "player payment");
        assertEquals(0, service.playerBalance(player).cents());
        assertEquals(9_500, service.playerBalance(recipient).cents());
        assertEquals(600, service.treasuryBalance().cents());
    }

    @Test
    void changedTaxPolicyAppliesToTheNextPayment() {
        TaxPolicy policy = new TaxPolicy(1, 5);
        service = new EconomyService(repository, policy);
        UUID recipient = UUID.randomUUID();
        repository.credit(AccountId.player(player), Money.ofCents(11_000), TransactionType.ISSUE, "opening balance");
        policy.setRate(TaxType.TRANSFER_FEE, 10);
        policy.setRate(TaxType.TRANSFER_INCOME, 20);
        service.transferPlayerFunds(player, recipient, Money.ofCents(10_000), "changed tax");
        assertEquals(8_000, service.playerBalance(recipient).cents());
        assertEquals(3_000, service.treasuryBalance().cents());
    }

    @Test
    void paymentFailsWithoutEnoughFundsForAmountAndFee() {
        UUID recipient = UUID.randomUUID();
        repository.credit(AccountId.player(player), Money.ofCents(10_099), TransactionType.ISSUE, "opening balance");
        assertThrows(IllegalStateException.class, () -> service.transferPlayerFunds(player, recipient, Money.ofCents(10_000), "payment"));
        assertEquals(10_099, service.playerBalance(player).cents());
    }
}
