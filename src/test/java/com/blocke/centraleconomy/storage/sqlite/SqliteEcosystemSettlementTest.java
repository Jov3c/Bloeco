package com.blocke.centraleconomy.storage.sqlite;

import com.blocke.bloeco.api.InstitutionCapability;
import com.blocke.bloeco.api.InstitutionDescriptor;
import com.blocke.bloeco.api.OperationContext;
import com.blocke.bloeco.api.SettlementKind;
import com.blocke.centraleconomy.application.CentralBankService;
import com.blocke.centraleconomy.application.TaxRuleService;
import com.blocke.centraleconomy.application.integration.InstitutionService;
import com.blocke.centraleconomy.application.integration.InstitutionSettlementService;
import com.blocke.centraleconomy.application.integration.SettlementRecord;
import com.blocke.centraleconomy.domain.money.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqliteEcosystemSettlementTest {
    @TempDir Path temporaryDirectory;

    @Test
    void receiptAndChineseMemoSurviveReopen() {
        Path database = temporaryDirectory.resolve("economy.db");
        UUID player = UUID.randomUUID();
        Clock clock = Clock.systemUTC();
        try (SqliteLedgerStore ledger = new SqliteLedgerStore(database)) {
            CentralBankService bank = new CentralBankService(ledger, clock);
            bank.initializeCentralAccounts();
            bank.bootstrapTreasury(Money.ofMinor(10_000));
            bank.grantStarterFunds(player, Money.ofMinor(2_000));
            TaxRuleService taxes = new TaxRuleService(ledger, clock);
            taxes.initializeDefaults();
            try (SqliteEcosystemStore store = new SqliteEcosystemStore(database)) {
                new InstitutionService(store, clock).registerOrResume("Bloeco-Shop", "test.Shop", "1.0",
                        new InstitutionDescriptor("bloeco.shop", "商店",
                                Set.of(InstitutionCapability.CHARGE_PLAYER), Set.of("sales")));
                new InstitutionSettlementService(store, taxes, clock).chargePlayer("bloeco.shop", player,
                        "sales", Money.ofMinor(1_000), SettlementKind.PURCHASE,
                        new OperationContext("shop.order", "CN-1", "购买一把钻石剑", "cn-1"));
            }
        }
        try (SqliteEcosystemStore store = new SqliteEcosystemStore(database)) {
            SettlementRecord restored = store.findOperation("bloeco.shop", "cn-1").orElseThrow();
            assertEquals("购买一把钻石剑", restored.displayMemo());
            assertEquals(1, store.recentTransactions("bloeco.shop", "sales", 10).size());
        }
    }
}
