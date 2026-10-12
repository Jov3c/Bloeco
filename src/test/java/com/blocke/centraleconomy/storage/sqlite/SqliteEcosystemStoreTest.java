package com.blocke.centraleconomy.storage.sqlite;

import com.blocke.bloeco.api.InstitutionCapability;
import com.blocke.bloeco.api.InstitutionDescriptor;
import com.blocke.centraleconomy.application.integration.InstitutionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqliteEcosystemStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void repeatedRegistrationDoesNotDuplicateInstitutionsAccountsOrBalances() throws Exception {
        Path database = temporaryDirectory.resolve("economy.db");
        InstitutionDescriptor descriptor = new InstitutionDescriptor(
                "bloeco.quest", "Bloeco 任务",
                Set.of(InstitutionCapability.PAY_PLAYER), Set.of("payout"));

        try (SqliteEcosystemStore store = new SqliteEcosystemStore(database)) {
            InstitutionService service = new InstitutionService(store, Clock.systemUTC());
            service.registerOrResume("Bloeco-Quest", "com.blocke.quest.QuestPlugin", "1.0.0", descriptor);
            service.registerOrResume("Bloeco-Quest", "com.blocke.quest.QuestPlugin", "1.0.1", descriptor);
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM institutions"));
            assertEquals(2, scalar(connection,
                    "SELECT COUNT(*) FROM accounts WHERE owner_id='bloeco.quest'"));
            assertEquals(2, scalar(connection, """
                    SELECT COUNT(*) FROM account_balances b
                    JOIN accounts a ON a.account_id=b.account_id
                    WHERE a.owner_id='bloeco.quest'
                    """));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM institution_capabilities"));
        }
    }

    private static int scalar(java.sql.Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
