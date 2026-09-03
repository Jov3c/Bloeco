package com.blocke.centraleconomy.vault;

import com.blocke.centraleconomy.ledger.AccountId;
import com.blocke.centraleconomy.ledger.SqliteLedgerRepository;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CentralEconomyVaultProviderTest {
    @TempDir
    Path temporaryDirectory;

    private SqliteLedgerRepository ledger;
    private CentralEconomyVaultProvider provider;
    private Player player;

    @BeforeEach
    void setUp() {
        var server = MockBukkit.mock();
        ledger = new SqliteLedgerRepository(temporaryDirectory.resolve("economy.db"));
        provider = new CentralEconomyVaultProvider(ledger, "金币", "金币");
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        ledger.close();
        MockBukkit.unmock();
    }

    @Test
    void depositCreditsThePlayerLedgerAccountAndInsufficientWithdrawalDoesNotChangeIt() {
        EconomyResponse deposited = provider.depositPlayer(player, 10.00d);

        assertEquals(EconomyResponse.ResponseType.SUCCESS, deposited.type);
        assertEquals(10.00d, provider.getBalance(player), 0.001d);
        assertEquals(1_000L, ledger.balance(AccountId.player(player.getUniqueId())).cents());

        EconomyResponse withdrawn = provider.withdrawPlayer(player, 10.01d);

        assertEquals(EconomyResponse.ResponseType.FAILURE, withdrawn.type);
        assertEquals(10.00d, provider.getBalance(player), 0.001d);
        assertEquals(1_000L, ledger.balance(AccountId.player(player.getUniqueId())).cents());
    }

    @Test
    void depositRejectsAmountsThatCannotBeRepresentedAsWholeCents() {
        EconomyResponse response = provider.depositPlayer(player, 10.001d);

        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
        assertEquals(0.00d, provider.getBalance(player), 0.001d);
        assertEquals(0L, ledger.balance(AccountId.player(player.getUniqueId())).cents());
    }
}
