package de.tum.cit.aet.hephaestus.testconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class DatabaseTestUtilsIntegrationTest extends BaseIntegrationTest {

    /** The partial-answer query of the ledger listener locks these two tables in the order opposite to the cleanup's. */
    @Test
    void shouldCleanWithoutADeadlockWhenAReaderLocksTwoTablesInTheOppositeOrder()
            throws SQLException, InterruptedException, ExecutionException, TimeoutException {
        cleanBesideATransaction("conversation_turn", "SELECT 1 FROM conversation_turn LIMIT 0");
    }

    @Test
    void shouldCleanWithoutADeadlockWhenAWriterHoldsASystemSecretRow()
            throws SQLException, InterruptedException, ExecutionException, TimeoutException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO system_secret (name, sealed_value) VALUES ('cleanup-fixture', '')");
        }

        cleanBesideATransaction(
                "system_secret", "SELECT 1 FROM system_secret WHERE name = 'cleanup-fixture' FOR UPDATE");
    }

    /**
     * Another transaction takes its first lock, the cleanup starts, and the transaction then reads
     * {@code conversation}. A cleanup that waited for the first lock while it held {@code conversation} would close a
     * cycle here.
     */
    private void cleanBesideATransaction(String heldTable, String firstStatement)
            throws SQLException, InterruptedException, ExecutionException, TimeoutException {
        long refusedBefore = refusals(heldTable);
        try (Connection other = connect();
                Statement statement = other.createStatement()) {
            other.setAutoCommit(false);
            statement.execute(firstStatement);

            CompletableFuture<Void> cleanup = CompletableFuture.runAsync(databaseTestUtils::cleanDatabase);
            // PostgreSQL logs this refusal only for a lock request that does not queue. The line thus proves, on a
            // runner of any speed, that the cleanup reached the held table and did not wait for it.
            await().atMost(Duration.ofSeconds(20)).until(() -> cleanup.isDone() || refusals(heldTable) > refusedBefore);
            assertThat(cleanup)
                    .as("the cleanup refuses to wait for %s", heldTable)
                    .isNotDone();
            statement.execute("SELECT 1 FROM conversation LIMIT 0");
            other.commit();

            cleanup.get(1, TimeUnit.MINUTES);
        }
    }

    private static Connection connect() throws SQLException {
        var database = PostgreSQLTestContainer.application();
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }

    private static long refusals(String table) {
        return Pattern.compile(Pattern.quote("could not obtain lock on relation \"" + table + "\""))
                .matcher(PostgreSQLTestContainer.getLogs())
                .results()
                .count();
    }
}
