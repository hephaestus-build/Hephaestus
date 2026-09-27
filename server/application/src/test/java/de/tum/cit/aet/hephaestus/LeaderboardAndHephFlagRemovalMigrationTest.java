package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * The changelog that retires the leaderboard and the workspace Heph flag, applied to a database that holds
 * rows from before it: Heph bindings, merge attribution, defaults for the columns the application stopped
 * writing, and the rollback of the changesets that can be reversed.
 */
@Tag("database")
class LeaderboardAndHephFlagRemovalMigrationTest {

    private static final String CHANGELOG = "1790509990788_changelog.xml";
    private static final Contexts CONTEXTS = new Contexts("prod");
    private static final LabelExpression LABELS = new LabelExpression();
    private static final TestDatabase DATABASE =
            PostgreSQLTestContainer.createDatabase("leaderboard_heph_flag_removal_migration");

    private static final String SEED = """
            INSERT INTO "user" (id, native_id, provider_id, login)
            SELECT seed.id, seed.id, provider.id, seed.login
            FROM identity_provider provider,
                (VALUES (992001, 'pull-request-author'), (992002, 'merger')) AS seed(id, login)
            WHERE provider.type = 'GITHUB' AND provider.server_url = 'https://github.com';
            INSERT INTO workspace (
                id, account_login, account_type, created_at, display_name, is_publicly_viewable,
                slug, status, mentor_enabled
            ) VALUES
                (992010, 'heph-off', 'ORG', now(), 'Heph off', false, 'heph-off', 'ACTIVE', false),
                (992011, 'heph-on', 'ORG', now(), 'Heph on', false, 'heph-on', 'ACTIVE', true),
                (992012, 'heph-off-disabled', 'ORG', now(), 'Heph off, disabled', false, 'heph-off-disabled',
                    'ACTIVE', false);
            INSERT INTO llm_connection (id, slug, display_name, base_url, api_protocol, created_at)
            VALUES (992020, 'migration-connection', 'Connection', 'https://api.openai.example/v1',
                'openai-completions', now());
            INSERT INTO llm_model (id, connection_id, slug, display_name, upstream_model_id, created_at)
            VALUES (992021, 992020, 'migration-model', 'Model', 'migration-model', now());
            INSERT INTO workspace_agent_binding (id, workspace_id, purpose, enabled, instance_model_id, updated_at)
            VALUES
                (992030, 992010, 'MENTOR', true, 992021, NULL),
                (992031, 992010, 'PRACTICE_REVIEW', true, 992021, NULL),
                (992032, 992011, 'MENTOR', true, 992021, NULL),
                (992033, 992012, 'MENTOR', false, 992021, '2026-01-01T00:00:00Z');
            INSERT INTO issue (
                id, issue_type, native_id, provider_id, number, comments_count, is_locked, author_id
            )
            SELECT seed.id, 'PULL_REQUEST', seed.id, provider.id, seed.number, 0, false, seed.author_id
            FROM identity_provider provider,
                (VALUES (992040, 1, 992001), (992041, 2, NULL)) AS seed(id, number, author_id)
            WHERE provider.type = 'GITHUB' AND provider.server_url = 'https://github.com';
            INSERT INTO activity_event (
                id, event_key, event_type, occurred_at, actor_id, workspace_id,
                target_type, target_id, xp, ingested_at
            ) VALUES
                ('00000000-0000-0000-0000-000000992050', 'merged-known-author', 'PULL_REQUEST_MERGED',
                    now(), 992002, 992011, 'pull_request', 992040, 0, now()),
                ('00000000-0000-0000-0000-000000992051', 'merged-unknown-author', 'PULL_REQUEST_MERGED',
                    now(), 992002, 992011, 'pull_request', 992041, 0, now()),
                ('00000000-0000-0000-0000-000000992052', 'closed-by-merger', 'PULL_REQUEST_CLOSED',
                    now(), 992002, 992011, 'pull_request', 992040, 0, now());
            """;

    @BeforeAll
    static void migrateAcrossTheChangelog() throws Exception {
        try (Connection connection = connect(DATABASE);
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
    void shouldDisableHephBindingsOnlyInWorkspacesThatHadHephOff() throws SQLException {
        assertThat(
                        column(
                                "SELECT id::text, enabled::text FROM workspace_agent_binding WHERE id BETWEEN 992030 AND 992032"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "992030", "false",
                        "992031", "true",
                        "992032", "true"));
    }

    @Test
    void shouldLeaveAnAlreadyDisabledHephBindingUntouchedWhenItsWorkspaceHadHephOff() throws SQLException {
        assertThat(column("SELECT id::text, (updated_at = '2026-01-01T00:00:00Z')::text"
                        + " FROM workspace_agent_binding WHERE id = 992033"))
                .containsExactlyEntriesOf(Map.of("992033", "true"));
    }

    @Test
    void shouldCreditAMergeToThePullRequestAuthorAndToNobodyWhenTheAuthorIsUnknown() throws SQLException {
        assertThat(column("SELECT event_key, coalesce(actor_id::text, 'nobody') FROM activity_event"
                        + " WHERE target_type = 'pull_request' AND workspace_id = 992011"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "merged-known-author", "992001",
                        "merged-unknown-author", "nobody",
                        "closed-by-merger", "992002"));
    }

    @Test
    void shouldAcceptInsertsThatOmitTheColumnsTheApplicationDoesNotWrite() throws SQLException {
        try (Connection connection = connect(DATABASE);
                var statement = connection.createStatement()) {
            statement.execute("""
                INSERT INTO activity_event (id, event_key, event_type, occurred_at, workspace_id, ingested_at)
                VALUES ('00000000-0000-0000-0000-000000992060', 'without-xp', 'ISSUE_CREATED', now(), 992010, now());
                INSERT INTO workspace_membership (workspace_id, user_id, role, created_at, hidden)
                VALUES (992011, 992001, 'MEMBER', now(), false);
                """);
        }

        assertThat(column("SELECT event_key, xp::text FROM activity_event WHERE event_key = 'without-xp'"))
                .containsExactlyEntriesOf(Map.of("without-xp", "0"));
        assertThat(column("SELECT user_id::text, league_points::text FROM workspace_membership WHERE user_id = 992001"))
                .containsExactlyEntriesOf(Map.of("992001", "0"));
    }

    @Test
    void shouldRestoreTheIndexesAndDefaultsWhenTheReversibleChangeSetsRollBack() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("leaderboard_heph_flag_removal_rollback");
        try (Connection connection = connect(database);
                Liquibase liquibase = liquibase(connection)) {
            liquibase.update(migrateToTheChangelog(liquibase), CONTEXTS, LABELS);

            liquibase.rollback(3, CONTEXTS, LABELS);

            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("""
                        SELECT to_regclass('public.idx_activity_event_xp_lookup') IS NOT NULL AS xp_lookup,
                            to_regclass('public.idx_activity_event_workspace_target') IS NOT NULL AS workspace_target,
                            to_regclass('public.idx_activity_event_leaderboard') IS NOT NULL AS leaderboard,
                            (SELECT count(*) FROM information_schema.columns
                                WHERE table_schema = 'public' AND column_default IS NOT NULL
                                AND ((table_name = 'activity_event' AND column_name = 'xp')
                                    OR (table_name = 'workspace_membership' AND column_name = 'league_points')))
                                AS defaults
                        """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBoolean("xp_lookup")).isTrue();
                assertThat(rows.getBoolean("workspace_target")).isFalse();
                assertThat(rows.getBoolean("leaderboard")).isTrue();
                assertThat(rows.getInt("defaults")).isZero();
            }
            assertThatThrownBy(() -> liquibase.rollback(1, CONTEXTS, LABELS))
                    .hasStackTraceContaining("restore a pre-upgrade database backup");
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

    private static Map<String, String> column(String query) throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        try (Connection connection = connect(DATABASE);
                var statement = connection.createStatement();
                var rows = statement.executeQuery(query)) {
            while (rows.next()) {
                values.put(rows.getString(1), rows.getString(2));
            }
        }
        return values;
    }

    private static Connection connect(TestDatabase database) throws SQLException {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }
}
