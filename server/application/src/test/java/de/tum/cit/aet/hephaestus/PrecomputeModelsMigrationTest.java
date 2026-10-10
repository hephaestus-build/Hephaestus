package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.PreconditionFailedException;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Runs the precompute models changelog against populated tables in their released shape. Only the columns and
 * constraints that the changelog reads or changes are created.
 */
@Tag("database")
class PrecomputeModelsMigrationTest {
    private static final String CHANGELOG = "db/changelog/1791486276211_changelog.xml";
    private static final List<String> NEW_PROTOCOLS = List.of("openai-decisions", "openai-embeddings", "cohere-rerank");
    private static final List<String> NEW_SOURCE_TYPES =
            List.of("PRECOMPUTE_DECISION", "PRECOMPUTE_EMBEDDING", "PRECOMPUTE_RERANKING");

    @Test
    void shouldKeepEveryStoredConnectionAndAcceptTheNewProtocolsWhenMigrated() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("llm_connection_protocol_migration");
        createReleasedSchema(database, true, true);
        for (String table : List.of("llm_connection", "workspace_llm_connection")) {
            execute(
                    database,
                    "INSERT INTO " + table + " (api_protocol) VALUES ('openai-completions'), ('openai-responses')");
        }

        migrate(database);

        for (String table : List.of("llm_connection", "workspace_llm_connection")) {
            assertThat(column(database, "SELECT api_protocol FROM " + table + " ORDER BY id"))
                    .containsExactly("openai-completions", "openai-responses");
            for (String protocol : NEW_PROTOCOLS) {
                execute(database, "INSERT INTO " + table + " (api_protocol) VALUES ('" + protocol + "')");
            }
            assertThatThrownBy(() ->
                            execute(database, "INSERT INTO " + table + " (api_protocol) VALUES ('anthropic-messages')"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void shouldKeepEveryLedgerRowAndAcceptThePrecomputeSourcesWhenMigrated() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("llm_usage_source_migration");
        createReleasedSchema(database, true, true);
        execute(database, """
                INSERT INTO llm_usage_event (id, source_type)
                VALUES ('00000000-0000-0000-0000-000000000001', 'AGENT_JOB'),
                       ('00000000-0000-0000-0000-000000000002', 'MENTOR_TURN')
                """);

        migrate(database);

        assertThat(column(database, "SELECT source_type FROM llm_usage_event ORDER BY id"))
                .containsExactly("AGENT_JOB", "MENTOR_TURN");
        for (String sourceType : NEW_SOURCE_TYPES) {
            execute(
                    database,
                    "INSERT INTO llm_usage_event (id, source_type) VALUES (gen_random_uuid(), '" + sourceType + "')");
        }
        assertThatThrownBy(() -> execute(
                        database,
                        "INSERT INTO llm_usage_event (id, source_type) VALUES (gen_random_uuid(), 'PRECOMPUTE_CHAT')"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void shouldCascadePrecomputeUsageWithItsJobAndRefuseAnUnknownKindWhenMigrated() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("precompute_usage_migration");
        createReleasedSchema(database, true, true);
        execute(database, "INSERT INTO workspace (id) VALUES (1)");
        execute(database, "INSERT INTO agent_job (id) VALUES ('00000000-0000-0000-0000-0000000000a1')");

        migrate(database);

        String insert = """
                INSERT INTO agent_job_precompute_usage
                    (job_id, attempt, model_kind, practice_slug, workspace_id, calls, input_tokens, output_tokens)
                VALUES ('00000000-0000-0000-0000-0000000000a1', 0, '%s', 'comment-quality', 1, 1, 10, 2)
                """;
        execute(database, insert.formatted("DECISION"));
        assertThatThrownBy(() -> execute(database, insert.formatted("SPEECH"))).isInstanceOf(SQLException.class);
        execute(database, "DELETE FROM agent_job WHERE id = '00000000-0000-0000-0000-0000000000a1'");
        assertThat(column(
                        database,
                        "SELECT model_kind FROM agent_job_precompute_usage "
                                + "WHERE job_id = '00000000-0000-0000-0000-0000000000a1'"))
                .isEmpty();
    }

    /**
     * A run goes with its job, keeps no revision that was deleted, and holds no models when the script ended
     * before it declared them.
     */
    @Test
    void shouldCascadeARunWithItsJobAndForgetADeletedRevisionWhenMigrated() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("precompute_run_migration");
        createReleasedSchema(database, true, true);
        execute(database, "INSERT INTO workspace (id) VALUES (1)");
        execute(database, "INSERT INTO practice_revision (id) VALUES (7)");
        execute(database, """
                INSERT INTO agent_job (id)
                VALUES ('00000000-0000-0000-0000-0000000000a1'), ('00000000-0000-0000-0000-0000000000a2')
                """);

        migrate(database);

        String insert = """
                INSERT INTO agent_job_precompute_run
                    (job_id, attempt, practice_slug, workspace_id, practice_revision_id, status, leads, models,
                     finished_at)
                VALUES ('%s', 0, 'comment-quality', 1, 7, '%s', 0, %s, now())
                """;
        execute(database, insert.formatted("00000000-0000-0000-0000-0000000000a1", "OK", "'[]'"));
        execute(database, insert.formatted("00000000-0000-0000-0000-0000000000a2", "TIMED_OUT", "NULL"));
        assertThatThrownBy(() ->
                        execute(database, insert.formatted("00000000-0000-0000-0000-0000000000a2", "CRASHED", "NULL")))
                .isInstanceOf(SQLException.class);

        execute(database, "DELETE FROM practice_revision WHERE id = 7");
        assertThat(column(database, "SELECT practice_revision_id FROM agent_job_precompute_run ORDER BY job_id"))
                .containsExactly(null, null);
        execute(database, "DELETE FROM agent_job WHERE id = '00000000-0000-0000-0000-0000000000a1'");
        assertThat(column(database, "SELECT status FROM agent_job_precompute_run"))
                .containsExactly("TIMED_OUT");
    }

    @Test
    void shouldHaltWhenTheConnectionConstraintToWidenIsMissing() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("llm_connection_protocol_missing");
        createReleasedSchema(database, false, true);

        assertThatThrownBy(() -> migrate(database)).hasRootCauseInstanceOf(PreconditionFailedException.class);
    }

    @Test
    void shouldHaltWhenTheLedgerConstraintToWidenIsMissing() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("llm_usage_source_missing");
        createReleasedSchema(database, true, false);

        assertThatThrownBy(() -> migrate(database)).hasRootCauseInstanceOf(PreconditionFailedException.class);
    }

    private static void createReleasedSchema(TestDatabase database, boolean protocolChecks, boolean sourceTypeCheck)
            throws SQLException {
        for (String table : List.of("llm_connection", "workspace_llm_connection")) {
            String constraint = table.equals("llm_connection")
                    ? "ck_llm_connection_api_protocol"
                    : "ck_ws_llm_connection_api_protocol";
            String check = protocolChecks
                    ? ", CONSTRAINT " + constraint
                            + " CHECK (api_protocol IN ('openai-completions', 'openai-responses'))"
                    : "";
            execute(database, """
                    CREATE TABLE %s (
                        id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        api_protocol varchar(40) NOT NULL%s
                    )
                    """.formatted(table, check));
        }
        execute(database, "CREATE TABLE workspace (id bigint PRIMARY KEY)");
        execute(database, "CREATE TABLE agent_job (id uuid PRIMARY KEY)");
        execute(database, "CREATE TABLE practice_revision (id bigint PRIMARY KEY)");
        String sourceCheck = sourceTypeCheck
                ? ", CONSTRAINT ck_llm_usage_event_source_type CHECK (source_type IN ('AGENT_JOB', 'MENTOR_TURN'))"
                : "";
        execute(database, """
                CREATE TABLE llm_usage_event (
                    id uuid PRIMARY KEY,
                    source_type varchar(20) NOT NULL%s
                )
                """.formatted(sourceCheck));
    }

    private static List<@Nullable String> column(TestDatabase database, String query) throws SQLException {
        try (Connection connection = connect(database);
                var statement = connection.createStatement();
                var rows = statement.executeQuery(query)) {
            List<@Nullable String> values = new ArrayList<>();
            while (rows.next()) values.add(rows.getString(1));
            return values;
        }
    }

    private static Connection connect(TestDatabase database) throws SQLException {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }

    private static void execute(TestDatabase database, String sql) throws SQLException {
        try (Connection connection = connect(database);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void migrate(TestDatabase database) throws Exception {
        var liquibaseDatabase =
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connect(database)));
        try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), liquibaseDatabase)) {
            liquibase.update(new Contexts());
        }
    }
}
