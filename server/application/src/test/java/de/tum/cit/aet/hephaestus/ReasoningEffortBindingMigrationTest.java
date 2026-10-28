package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.IntStream;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The changelog that moves the reasoning effort from the model to the agent binding, applied to bindings and models
 * from before it: reviews and decisions keep their model's effort, Heph asks for at most LOW, embedding takes none,
 * Heph timeouts at the former ceiling get the new one, and a rollback gives a model back its reviews' effort.
 */
@Tag("database")
class ReasoningEffortBindingMigrationTest {

    private static final String CHANGELOG = "1793135595972_changelog.xml";
    private static final Contexts CONTEXTS = new Contexts("prod");
    private static final LabelExpression LABELS = new LabelExpression();
    private static final TestDatabase DATABASE =
            PostgreSQLTestContainer.createDatabase("reasoning_effort_binding_migration");

    private static final String SEED = """
            INSERT INTO workspace (id, account_login, account_type, display_name, slug, status)
            VALUES (993001, 'effort-move', 'ORG', 'Effort move', 'effort-move', 'ACTIVE');
            INSERT INTO llm_connection (id, slug, display_name, base_url, api_protocol, created_at) VALUES
                (993010, 'effort-shared', 'Shared', 'https://models.example.test/v1', 'openai-completions', now());
            INSERT INTO llm_model (
                id, connection_id, slug, display_name, upstream_model_id, created_at, reasoning_effort
            ) VALUES
                (993011, 993010, 'qwen-medium', 'Qwen at medium', 'qwen-medium', now(), 'MEDIUM'),
                (993012, 993010, 'qwen-none', 'Qwen at none', 'qwen-none', now(), 'NONE'),
                (993013, 993010, 'no-effort', 'Model without an effort', 'no-effort', now(), NULL),
                (993014, 993010, 'embedder', 'Embedder', 'embedder', now(), 'HIGH');
            INSERT INTO workspace_llm_connection (
                id, workspace_id, slug, display_name, base_url, api_protocol, created_at
            ) VALUES (993020, 993001, 'effort-own', 'Own', 'https://own.example.test/v1', 'openai-completions', now());
            INSERT INTO workspace_llm_model (
                id, workspace_id, connection_id, slug, display_name, upstream_model_id, created_at, reasoning_effort
            ) VALUES (993021, 993001, 993020, 'own-high', 'Own at high', 'own-high', now(), 'HIGH');
            INSERT INTO workspace_agent_binding (
                id, workspace_id, purpose, data_handling_tier, timeout_seconds, instance_model_id, workspace_model_id
            ) VALUES
                (993030, 993001, 'PRACTICE_REVIEW', 'UNDECLARED', 10800, 993011, NULL),
                (993031, 993001, 'MENTOR', 'UNDECLARED', 300, 993011, NULL),
                (993032, 993001, 'MENTOR', 'IN_HOUSE', 120, 993012, NULL),
                (993033, 993001, 'MENTOR', 'CLOUD', 10800, 993013, NULL),
                (993034, 993001, 'PRACTICE_REVIEW', 'IN_HOUSE', 3600, NULL, 993021),
                (993035, 993001, 'PRACTICE_DECISION', 'UNDECLARED', 10800, 993012, NULL),
                (993036, 993001, 'PRACTICE_EMBEDDING', 'UNDECLARED', 10800, 993014, NULL),
                (993037, 993001, 'PRACTICE_REVIEW', 'CLOUD', 10800, 993013, NULL);
            """;

    @BeforeAll
    static void migrateAcrossTheChangelog() throws Exception {
        try (Connection connection = connect();
                Liquibase liquibase = liquibase(connection)) {
            int changeSets = migrateToTheChangelog(liquibase);
            try (var statement = connection.createStatement()) {
                statement.execute(SEED);
            }
            connection.commit();
            liquibase.update(changeSets, CONTEXTS, LABELS);
        }
    }

    @Test
    void shouldKeepTheModelsEffortOnReviewsAndDecisionsAndCapHephAtLow() throws SQLException {
        assertThat(rows("""
                SELECT id::text, purpose || ' ' || coalesce(reasoning_effort, 'none sent')
                FROM workspace_agent_binding WHERE id BETWEEN 993030 AND 993037
                """))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "993030", "PRACTICE_REVIEW MEDIUM",
                        "993031", "MENTOR LOW",
                        "993032", "MENTOR NONE",
                        "993033", "MENTOR none sent",
                        "993034", "PRACTICE_REVIEW HIGH",
                        "993035", "PRACTICE_DECISION NONE",
                        "993036", "PRACTICE_EMBEDDING none sent",
                        "993037", "PRACTICE_REVIEW none sent"));
    }

    @Test
    void shouldGiveHephBindingsAtTheFormerCeilingTheNewOneAndKeepAShorterTimeout() throws SQLException {
        assertThat(rows("SELECT id::text, timeout_seconds::text FROM workspace_agent_binding"
                        + " WHERE id BETWEEN 993030 AND 993037"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "993030", "10800",
                        "993031", "900",
                        "993032", "120",
                        "993033", "900",
                        "993034", "3600",
                        "993035", "10800",
                        "993036", "10800",
                        "993037", "10800"));
    }

    @Test
    void shouldDropTheEffortOfBothModelCatalogs() throws SQLException {
        assertThat(rows("""
                SELECT table_name, column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND column_name = 'reasoning_effort'
                """)).containsExactlyEntriesOf(Map.of("workspace_agent_binding", "reasoning_effort"));
    }

    @Test
    void shouldGiveAModelBackTheEffortItsReviewsAgreeOnWhenTheChangelogRollsBack() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("reasoning_effort_binding_rollback");
        try (Connection connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                Liquibase liquibase = liquibase(connection)) {
            int count = migrateToTheChangelog(liquibase);
            try (var statement = connection.createStatement()) {
                statement.execute(SEED);
            }
            connection.commit();
            liquibase.update(count, CONTEXTS, LABELS);

            liquibase.rollback(count, CONTEXTS, LABELS);

            assertThat(rows(connection, """
                    SELECT 'instance ' || id, coalesce(reasoning_effort, 'none sent') FROM llm_model
                    WHERE id BETWEEN 993011 AND 993013
                    UNION ALL
                    SELECT 'own ' || id, coalesce(reasoning_effort, 'none sent') FROM workspace_llm_model
                    WHERE id = 993021
                    UNION ALL
                    SELECT 'binding column', count(*)::text FROM information_schema.columns
                    WHERE table_name = 'workspace_agent_binding' AND column_name = 'reasoning_effort'
                    """))
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            "instance 993011", "MEDIUM",
                            // Only a decision and Heph used it: no review recorded its effort.
                            "instance 993012", "none sent",
                            "instance 993013", "none sent",
                            "own 993021", "HIGH",
                            "binding column", "0"));
        }
    }

    /** Applies the chain up to the changelog under test and returns how many changesets the changelog holds. */
    private static int migrateToTheChangelog(Liquibase liquibase) throws Exception {
        var pending = liquibase.listUnrunChangeSets(CONTEXTS, LABELS);
        int before = IntStream.range(0, pending.size())
                .filter(index -> pending.get(index).getFilePath().endsWith(CHANGELOG))
                .findFirst()
                .orElseThrow();
        liquibase.update(before, CONTEXTS, LABELS);
        return (int) pending.stream()
                .filter(changeSet -> changeSet.getFilePath().endsWith(CHANGELOG))
                .count();
    }

    private static Liquibase liquibase(Connection connection) throws Exception {
        var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
        return new Liquibase("db/master.xml", new ClassLoaderResourceAccessor(), database);
    }

    private static Map<String, String> rows(String query) throws SQLException {
        try (Connection connection = connect()) {
            return rows(connection, query);
        }
    }

    private static Map<String, String> rows(Connection connection, String query) throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(query)) {
            while (result.next()) {
                values.put(result.getString(1), result.getString(2));
            }
        }
        return values;
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
    }
}
