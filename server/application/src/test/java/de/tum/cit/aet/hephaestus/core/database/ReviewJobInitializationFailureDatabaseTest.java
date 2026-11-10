package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** The repository's own initializer-failure statement against PostgreSQL: it writes once, and only for its attempt. */
@Tag("database")
class ReviewJobInitializationFailureDatabaseTest {
    private static final long WORKSPACE = 978001;
    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-000000978201");
    private static final UUID OTHER_RUN = UUID.fromString("00000000-0000-0000-0000-000000978202");
    private static final String DIAGNOSIS =
            "{\"attempt\":1,\"exitCode\":2,\"timedOut\":false,\"transcriptTail\":\"tar\"}";

    @Test
    void shouldRecordTheInitializerFailureOnlyOnItsFailedAttemptAndKeepTheRestOfTheCapture() throws Exception {
        var database = NativePracticeFixture.resultParent("dev");
        var jdbc = new NamedParameterJdbcTemplate(
                new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password()));
        String statement = ReviewJobRepository.class
                .getMethod("recordInitializationFailure", UUID.class, long.class, int.class, UUID.class, String.class)
                .getAnnotation(Query.class)
                .value();
        UUID withoutCapture = UUID.fromString("00000000-0000-0000-0000-000000978101");
        UUID withCapture = UUID.fromString("00000000-0000-0000-0000-000000978102");
        insertFailedJob(database, withoutCapture, RUN, null);
        insertFailedJob(database, withCapture, OTHER_RUN, "{\"manifest\":{\"files\":[]}}");

        // A NULL capture gains the key alone; a present capture keeps its other keys.
        assertThat(record(jdbc, statement, withoutCapture, 1, RUN)).isEqualTo(1);
        assertThat(record(jdbc, statement, withCapture, 1, OTHER_RUN)).isEqualTo(1);
        // Another attempt or another run writes nothing.
        assertThat(record(jdbc, statement, withoutCapture, 0, RUN)).isZero();
        assertThat(record(jdbc, statement, withoutCapture, 1, OTHER_RUN)).isZero();

        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT capture->'initializationFailure'->>'exitCode' FROM review_job WHERE id='"
                                + withoutCapture + "'"))
                .containsExactly("2");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT (capture->'manifest' IS NOT NULL)::text || ':' || (capture->'initializationFailure'->>'exitCode')"
                                + " FROM review_job WHERE id='" + withCapture + "'"))
                .containsExactly("true:2");
    }

    private static int record(NamedParameterJdbcTemplate jdbc, String statement, UUID id, int attempt, UUID runId) {
        return jdbc.update(
                statement,
                Map.of("id", id, "workspaceId", WORKSPACE, "attempt", attempt, "runId", runId, "diagnosis", DIAGNOSIS));
    }

    /** A job that failed on attempt 1: its run ended after its claim numbered 2. */
    private static void insertFailedJob(TestDatabase database, UUID id, UUID runId, @Nullable String capture) {
        PracticeMigrationFixture.execute(database, """
                INSERT INTO agent_run
                    (id,workspace_id,kind,band,concurrency_key,state,attempt,lease_epoch,stop_reason,started_at,ended_at)
                VALUES ('%s',%d,'REVIEW','BATCH','review:%s','ENDED',0,2,'COMPLETED',now(),now());
                INSERT INTO review_job
                    (id,review_id,workspace_id,submitted_at,status,processing_status,trace_id,input,config_snapshot,
                     run_id,capture,completed_at)
                VALUES ('%s','00000000-0000-0000-0000-000000978011',%d,now(),
                        'FAILED','DONE','00000000000000000000000000000001','{}','{}','%s',%s,now())
                """.formatted(
                        runId, WORKSPACE, id, id, WORKSPACE, runId, capture == null ? "NULL" : "'" + capture + "'"));
    }
}
