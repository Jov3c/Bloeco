package com.blocke.centraleconomy.storage.mysql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlEcosystemConcurrencyTest {
    @Test
    void productionStoreUsesVersionedOrderedBalanceLocking() throws Exception {
        String source=java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/blocke/centraleconomy/storage/mysql/MySqlEcosystemStore.java"));
        assertTrue(source.contains("lockInOrder"));
        assertTrue(source.contains("reservedDeltas"));
        assertEquals(7,MySqlMigrations.LATEST_VERSION);
    }
}
