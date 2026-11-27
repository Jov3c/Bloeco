package com.blocke.centraleconomy.application.integration;

import com.blocke.bloeco.api.EconomyError;
import com.blocke.bloeco.api.InstitutionDescriptor;
import com.blocke.centraleconomy.application.CentralBankService;
import com.blocke.centraleconomy.application.TaxRuleService;
import com.blocke.centraleconomy.application.result.Result;
import com.blocke.centraleconomy.domain.money.Money;
import com.blocke.bloeco.api.MoneyAmount;
import com.blocke.centraleconomy.storage.sqlite.SqliteLedgerStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncInstitutionFacadeTest {
    @TempDir Path temporaryDirectory;

    @Test
    void executesDatabaseWorkOffCallerThreadAndFailsClosedAfterClose() {
        Path db=temporaryDirectory.resolve("economy.db"); Clock clock=Clock.systemUTC();
        UUID player = UUID.randomUUID();
        try(SqliteLedgerStore bootstrap=new SqliteLedgerStore(db)){
            CentralBankService bank=new CentralBankService(bootstrap,clock);bank.initializeCentralAccounts();
            bank.bootstrapTreasury(Money.ofMinor(10_000));new TaxRuleService(bootstrap,clock).initializeDefaults();
            bank.grantStarterFunds(player, Money.ofMinor(1_000));
        }
        AsyncInstitutionFacade facade=AsyncInstitutionFacade.sqlite(db,CompletableFuture.completedFuture(
                Result.success(null)),()->false,8);
        var connected=facade.connect("Test-Shop","test.Shop","1.0",
                InstitutionDescriptor.of("test.shop","测试商店")).toCompletableFuture().join();
        assertTrue(connected.isSuccess());
        var limited = facade.connect("Test-Events", "test.Events", "1.0",
                new InstitutionDescriptor("test.events", "测试事件", Set.of(), Set.of()))
                .toCompletableFuture().join().value();
        assertEquals(EconomyError.CAPABILITY_DENIED, limited.walletBalance(player).toCompletableFuture().join().error());
        assertEquals(new MoneyAmount(1_000), connected.value().walletBalance(player).toCompletableFuture().join().value());
        facade.close();
        assertEquals(EconomyError.STORAGE_UNAVAILABLE,facade.connect("Test-Shop","test.Shop","1.0",
                InstitutionDescriptor.of("test.shop","测试商店")).toCompletableFuture().join().error());
    }
}
