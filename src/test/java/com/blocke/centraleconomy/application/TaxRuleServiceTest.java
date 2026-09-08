package com.blocke.centraleconomy.application;

import com.blocke.centraleconomy.domain.account.Account;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.tax.TaxCategory;
import com.blocke.centraleconomy.domain.tax.TaxRule;
import com.blocke.centraleconomy.storage.sqlite.SqliteLedgerStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaxRuleServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC);

    @TempDir Path temporaryDirectory;
    private SqliteLedgerStore store;
    private TaxRuleService taxes;

    @BeforeEach
    void setUp() {
        store = new SqliteLedgerStore(temporaryDirectory.resolve("economy.db"));
        store.createAccount(Account.treasury());
        store.createAccount(Account.taxRevenue());
        store.createAccount(Account.feeRevenue());
        taxes = new TaxRuleService(store, CLOCK);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void changingARuleKeepsHistoricalVersionImmutable() {
        TaxRule first = taxes.change(TaxCategory.PLAYER_TRANSFER_FEE, 100, 0,
                "admin:tax", "initial fee");
        TaxRule second = taxes.change(TaxCategory.PLAYER_TRANSFER_FEE, 150, 0,
                "admin:tax", "adjusted fee");

        assertNotEquals(first.versionId(), second.versionId());
        TaxRule historical = taxes.byVersion(first.versionId());
        assertEquals(100, historical.basisPoints());
        assertNotNull(historical.effectiveUntil());
        assertEquals(150, taxes.current(TaxCategory.PLAYER_TRANSFER_FEE).basisPoints());
        assertEquals(AccountId.feeRevenue(), second.destinationAccountId());
    }

    @Test
    void initializesDefaultsOnlyWhenRulesDoNotExist() {
        taxes.initializeDefaults();
        taxes.initializeDefaults();

        assertEquals(100, taxes.current(TaxCategory.PLAYER_TRANSFER_FEE).basisPoints());
        assertEquals(500, taxes.current(TaxCategory.PLAYER_TRANSFER_INCOME).basisPoints());
        assertEquals(2, store.taxRuleCount());
    }

    @Test
    void rejectsInvalidRatesAndFixedFees() {
        assertThrows(IllegalArgumentException.class, () -> taxes.change(
                TaxCategory.PLAYER_TRANSFER_FEE, 10_001, 0, "admin:tax", "invalid"));
        assertThrows(IllegalArgumentException.class, () -> taxes.change(
                TaxCategory.PLAYER_TRANSFER_FEE, 100, -1, "admin:tax", "invalid"));
    }
}
