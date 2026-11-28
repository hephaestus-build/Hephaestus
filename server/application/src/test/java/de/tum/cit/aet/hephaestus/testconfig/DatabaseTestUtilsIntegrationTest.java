package de.tum.cit.aet.hephaestus.testconfig;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class DatabaseTestUtilsIntegrationTest extends BaseIntegrationTest {

    /**
     * The reader locks {@code conversation_turn} before {@code conversation}, as the partial-answer query of the ledger
     * listener does. The cleanup lists the two tables in the opposite order.
     */
    @Test
    void shouldCleanWithoutADeadlockWhenAReaderLocksTwoTablesInTheOppositeOrder()
            throws SQLException, InterruptedException, ExecutionException, TimeoutException {
        var database = PostgreSQLTestContainer.application();
        try (Connection reader =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                Statement statement = reader.createStatement()) {
            reader.setAutoCommit(false);
            statement.execute("SELECT 1 FROM conversation_turn LIMIT 0");

            CompletableFuture<Void> cleanup = CompletableFuture.runAsync(databaseTestUtils::cleanDatabase);
            // Less than PostgreSQL's deadlock_timeout, and enough for the cleanup to reach the held table.
            assertThatThrownBy(() -> cleanup.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            statement.execute("SELECT 1 FROM conversation LIMIT 0");
            reader.commit();

            cleanup.get(1, TimeUnit.MINUTES);
        }
    }
}
