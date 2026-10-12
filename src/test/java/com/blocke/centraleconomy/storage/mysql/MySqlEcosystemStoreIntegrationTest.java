package com.blocke.centraleconomy.storage.mysql;

import com.blocke.bloeco.api.InstitutionCapability;
import com.blocke.bloeco.api.InstitutionDescriptor;
import com.blocke.bloeco.api.OperationContext;
import com.blocke.bloeco.api.SettlementKind;
import com.blocke.centraleconomy.application.CentralBankService;
import com.blocke.centraleconomy.application.TaxRuleService;
import com.blocke.centraleconomy.application.integration.InstitutionService;
import com.blocke.centraleconomy.application.integration.InstitutionSettlementService;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.ledger.JournalEntry;
import com.blocke.centraleconomy.domain.ledger.JournalType;
import com.blocke.centraleconomy.domain.ledger.Posting;
import com.blocke.centraleconomy.domain.money.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MySqlEcosystemStoreIntegrationTest {
    @Test
    @EnabledIfEnvironmentVariable(named="BLOECO_MYSQL_TEST_URL",matches=".+")
    void mysqlRegistersAndSettlesWithConservedBalances() {
        String run=UUID.randomUUID().toString().substring(0,8), client="test.shop."+run;
        UUID player=UUID.randomUUID(); Clock clock=Clock.systemUTC();
        try(BloecoDataSource source=new BloecoDataSource(System.getenv("BLOECO_MYSQL_TEST_URL"),
                System.getenv("BLOECO_MYSQL_TEST_USER"),System.getenv("BLOECO_MYSQL_TEST_PASSWORD"),8)){
            MySqlTransactionManager tx=new MySqlTransactionManager(source.dataSource());
            try(MySqlLedgerStore ledger=new MySqlLedgerStore(source,tx);
                MySqlEcosystemStore ecosystem=new MySqlEcosystemStore(source,tx)){
                CentralBankService bank=new CentralBankService(ledger,clock);bank.initializeCentralAccounts();
                ledger.commit(JournalEntry.create(UUID.randomUUID(),JournalType.ISSUE,"ecosystem test issue",
                        client,"issue-"+run,Instant.now(),List.of(new Posting(AccountId.issuanceControl(),-20_000),
                                new Posting(AccountId.treasury(),20_000))));
                bank.grantStarterFunds(player,Money.ofMinor(20_000));
                TaxRuleService taxes=new TaxRuleService(ledger,clock);taxes.initializeDefaults();
                new InstitutionService(ecosystem,clock).registerOrResume("Test-Shop-"+run,"test.Shop","1.0",
                        new InstitutionDescriptor(client,"测试商店",Set.of(InstitutionCapability.CHARGE_PLAYER),
                                Set.of("sales")));
                var receipt=new InstitutionSettlementService(ecosystem,taxes,clock).chargePlayer(client,player,"sales",
                        Money.ofMinor(1_000), SettlementKind.PURCHASE,
                        new OperationContext("test.order",run,"MySQL 购买测试","charge-"+run));
                assertEquals(50,receipt.tax().minor());
                assertEquals(18_950,ecosystem.balance(AccountId.player(player)));
                assertEquals(1_000,ecosystem.balance(AccountId.plugin(client,"sales")));
            }
        }
    }
}
