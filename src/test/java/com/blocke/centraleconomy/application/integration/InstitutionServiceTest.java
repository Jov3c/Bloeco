package com.blocke.centraleconomy.application.integration;

import com.blocke.bloeco.api.ApiVersion;
import com.blocke.bloeco.api.InstitutionCapability;
import com.blocke.bloeco.api.InstitutionDescriptor;
import com.blocke.centraleconomy.domain.ledger.LedgerException;
import com.blocke.centraleconomy.storage.sqlite.SqliteEcosystemStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InstitutionServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T08:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void registrationCreatesStableInstitutionAccountsAndCapabilities() {
        Path database = temporaryDirectory.resolve("economy.db");
        InstitutionDescriptor descriptor = new InstitutionDescriptor(
                "bloeco.shop", "Bloeco 商店",
                Set.of(InstitutionCapability.CHARGE_PLAYER, InstitutionCapability.REFUND),
                Set.of("sales", "refund"));

        InstitutionRecord first;
        try (SqliteEcosystemStore store = new SqliteEcosystemStore(database)) {
            first = new InstitutionService(store, CLOCK).registerOrResume(
                    "Bloeco-Shop", "com.blocke.shop.BloecoShopPlugin", "1.0.0", descriptor);
        }
        try (SqliteEcosystemStore store = new SqliteEcosystemStore(database)) {
            InstitutionRecord resumed = new InstitutionService(store, CLOCK).registerOrResume(
                    "Bloeco-Shop", "com.blocke.shop.BloecoShopPlugin", "1.1.0", descriptor);

            assertEquals(first.clientId(), resumed.clientId());
            assertEquals(first.accounts(), resumed.accounts());
            assertEquals(Set.of("operating", "sales", "refund"), resumed.accounts().keySet());
            assertEquals(descriptor.capabilities(), resumed.capabilities());
            assertEquals(ApiVersion.CURRENT.major(), resumed.apiMajor());
            assertEquals("1.1.0", resumed.pluginVersion());
        }
    }

    @Test
    void existingClientIdCannotBeClaimedByDifferentPluginIdentity() {
        Path database = temporaryDirectory.resolve("economy.db");
        InstitutionDescriptor descriptor = InstitutionDescriptor.of("bloeco.land", "Bloeco 领地");

        try (SqliteEcosystemStore store = new SqliteEcosystemStore(database)) {
            InstitutionService service = new InstitutionService(store, CLOCK);
            service.registerOrResume("Bloeco-Land", "com.blocke.land.LandPlugin", "1.0.0", descriptor);

            LedgerException failure = assertThrows(LedgerException.class,
                    () -> service.registerOrResume(
                            "Fake-Land", "evil.plugin.FakePlugin", "9.9.9", descriptor));
            assertEquals(LedgerException.Code.UNAUTHORIZED, failure.code());
        }
    }
}
