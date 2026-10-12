package com.blocke.centraleconomy.application.integration;

import com.blocke.bloeco.api.EconomyError;
import com.blocke.bloeco.api.InstitutionDescriptor;
import com.blocke.centraleconomy.application.CentralBankService;
import com.blocke.centraleconomy.application.TaxRuleService;
import com.blocke.centraleconomy.application.result.Result;
import com.blocke.centraleconomy.domain.money.Money;
import com.blocke.centraleconomy.storage.sqlite.SqliteEcosystemStore;
import com.blocke.centraleconomy.storage.sqlite.SqliteLedgerStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncInstitutionFacadeTest {
    @TempDir Path temporaryDirectory;

    @Test
    void executesDatabaseWorkOffCallerThreadAndFailsClosedAfterClose() {
        Path db=temporaryDirectory.resolve("economy.db"); Clock clock=Clock.systemUTC();
        try(SqliteLedgerStore bootstrap=new SqliteLedgerStore(db)){
            CentralBankService bank=new CentralBankService(bootstrap,clock);bank.initializeCentralAccounts();
            bank.bootstrapTreasury(Money.ofMinor(10_000));new TaxRuleService(bootstrap,clock).initializeDefaults();
        }
        AsyncInstitutionFacade facade=AsyncInstitutionFacade.sqlite(db,CompletableFuture.completedFuture(
                Result.success(null)),()->false,8);
        var connected=facade.connect("Test-Shop","test.Shop","1.0",
                InstitutionDescriptor.of("test.shop","测试商店")).toCompletableFuture().join();
        assertTrue(connected.isSuccess());
        facade.close();
        assertEquals(EconomyError.STORAGE_UNAVAILABLE,facade.connect("Test-Shop","test.Shop","1.0",
                InstitutionDescriptor.of("test.shop","测试商店")).toCompletableFuture().join().error());
    }
}
