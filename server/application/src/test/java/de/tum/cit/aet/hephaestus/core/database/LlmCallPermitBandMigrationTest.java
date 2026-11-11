package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A call permit's class becomes its band, and a call without a workspace is a background call. */
@Tag("database")
class LlmCallPermitBandMigrationTest {

    private static final String CHANGELOG = "run-permit-bands.xml";
    private static final String CHECK = "ck_llm_call_permit_band";
    private static final Contexts CONTEXTS = new Contexts("prod");
    private static final LabelExpression LABELS = new LabelExpression();
    private static final TestDatabase DATABASE = PostgreSQLTestContainer.createDatabase("llm_call_permit_band");

    @Test
    void shouldGiveEachPermitTheBandOfItsClassWhenTheChangelogRuns() throws Exception {
        try (Connection connection = connect();
                Liquibase liquibase = liquibase(connection)) {
            var unrun = liquibase.listUnrunChangeSets(CONTEXTS, LABELS);
            int before = 0;
            while (!unrun.get(before).getFilePath().endsWith(CHANGELOG)) before++;
            liquibase.update(before, CONTEXTS, LABELS);
            pool();
            UUID turn = insert("call_class", "TURN", 2L);
            UUID request = insert("call_class", "REQUEST", 2L);
            UUID event = insert("call_class", "EVENT", 2L);
            UUID composition = insert("call_class", "COMPOSITION", 2L);
            UUID schedule = insert("call_class", "SCHEDULE", 2L);
            UUID backfill = insert("call_class", "BACKFILL", 2L);
            UUID feedback = insert("call_class", "PRODUCT_FEEDBACK", null);

            liquibase.update(1, CONTEXTS, LABELS);

            assertThat(bandOf(turn)).isEqualTo("INTERACTIVE");
            assertThat(bandOf(request)).isEqualTo("BATCH");
            assertThat(bandOf(event)).isEqualTo("BATCH");
            assertThat(bandOf(composition)).isEqualTo("BATCH");
            assertThat(bandOf(schedule)).isEqualTo("BACKGROUND");
            assertThat(bandOf(backfill)).isEqualTo("BACKGROUND");
            assertThat(bandOf(feedback)).isEqualTo("BACKGROUND");
            assertThat(bandOf(insert("band", "BACKGROUND", null))).isEqualTo("BACKGROUND");
            assertThatThrownBy(() -> insert("band", "BATCH", null)).hasMessageContaining(CHECK);
            assertThatThrownBy(() -> insert("band", "TURN", 2L)).hasMessageContaining(CHECK);

            liquibase.rollback(1, CONTEXTS, LABELS);

            assertThat(classOf(turn)).isEqualTo("TURN");
            assertThat(classOf(request)).isEqualTo("REQUEST");
            assertThat(classOf(schedule)).isEqualTo("SCHEDULE");
            assertThat(classOf(feedback)).isEqualTo("PRODUCT_FEEDBACK");
        }
    }

    private static void pool() throws SQLException {
        try (Connection connection = connect();
                var statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO llm_call_pool (id, slug, display_name, created_at) VALUES (1, 'pool', 'Pool',"
                            + " clock_timestamp())");
        }
    }

    private static UUID insert(String column, String value, @Nullable Long workspace) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = connect();
                var statement = connection.prepareStatement("""
                        INSERT INTO llm_call_permit (id, pool_id, worker_id, %s, workspace_id, source_id, attempt,
                            state, acquired_at, renewed_at, requests, tokens)
                        VALUES (?, 1, 'worker', ?, ?, ?, 0, 'WAITING', clock_timestamp(), clock_timestamp(), 0, 0)
                        """.formatted(column))) {
            statement.setObject(1, id);
            statement.setString(2, value);
            statement.setObject(3, workspace, Types.BIGINT);
            statement.setObject(4, UUID.randomUUID());
            statement.executeUpdate();
        }
        return id;
    }

    private static String bandOf(UUID permit) throws SQLException {
        return read("band", permit);
    }

    private static String classOf(UUID permit) throws SQLException {
        return read("call_class", permit);
    }

    private static String read(String column, UUID permit) throws SQLException {
        try (Connection connection = connect();
                var statement =
                        connection.prepareStatement("SELECT %s FROM llm_call_permit WHERE id = ?".formatted(column))) {
            statement.setObject(1, permit);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    private static Liquibase liquibase(Connection connection) throws Exception {
        var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
        var liquibase = new Liquibase("db/master.xml", new ClassLoaderResourceAccessor(), database);
        // This fixture resumes a deliberately partial history before the production entry boundary.
        liquibase
                .getDatabaseChangeLog()
                .getPreconditions()
                .getNestedPreconditions()
                .clear();
        return liquibase;
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
    }
}
