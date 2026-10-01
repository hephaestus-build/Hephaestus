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
 * rows from before it: the Heph setting and merge attribution are carried over before the leaderboard, league,
 * XP and Heph flag columns are dropped, and a rollback recreates those columns as the previous release had them.
 */
@Tag("database")
class LeaderboardAndHephFlagRemovalMigrationTest {

    private static final String CHANGELOG = "1790509990788_changelog.xml";
    private static final Contexts CONTEXTS = new Contexts("prod");
    private static final LabelExpression LABELS = new LabelExpression();
    private static final TestDatabase DATABASE =
            PostgreSQLTestContainer.createDatabase("leaderboard_heph_flag_removal_migration");

    /** Every column the changelog drops, as SQL {@code (table, column)} rows. */
    private static final String DROPPED = """
            ('activity_event', 'xp'), ('workspace_membership', 'league_points'),
            ('workspace', 'mentor_enabled'), ('workspace', 'leaderboard_enabled'), ('workspace', 'progression_enabled'),
            ('workspace', 'leagues_enabled'), ('workspace', 'leaderboard_schedule_day'),
            ('workspace', 'leaderboard_schedule_time'), ('workspace', 'leaderboard_notification_enabled'),
            ('workspace', 'leaderboard_league_cycle_at')
            """;

    /** Everything after the two data changesets: the index changes, and the drops of index, constraint and columns. */
    private static final int REVERSIBLE_CHANGE_SETS = 14;

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
    void shouldDropTheLeaderboardLeagueXpAndHephFlagColumnsWhenTheChangelogIsApplied() throws SQLException {
        assertThat(column("""
                SELECT table_name || '.' || column_name, data_type FROM information_schema.columns
                WHERE table_schema = 'public' AND (table_name, column_name) IN (%s)
                """.formatted(DROPPED))).isEmpty();
        assertThat(column("""
                SELECT name, coalesce(to_regclass('public.' || name)::text, 'gone') FROM (VALUES
                    ('idx_activity_event_leaderboard_covering'), ('idx_activity_event_leaderboard')) AS index(name)
                """))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "idx_activity_event_leaderboard_covering", "gone",
                        "idx_activity_event_leaderboard", "gone"));
        assertThat(column("""
                SELECT conname, contype::text FROM pg_constraint
                WHERE conrelid = 'public.activity_event'::regclass AND conname = 'chk_activity_event_xp_non_negative'
                """)).isEmpty();
    }

    @Test
    void shouldRecreateTheDroppedColumnsAsThePreviousReleaseHadThemWhenTheChangelogRollsBack() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("leaderboard_heph_flag_removal_rollback");
        try (Connection connection = connect(database);
                Liquibase liquibase = liquibase(connection)) {
            liquibase.update(migrateToTheChangelog(liquibase), CONTEXTS, LABELS);
            try (var statement = connection.createStatement()) {
                statement.execute("""
                    INSERT INTO "user" (id, native_id, provider_id, login)
                    SELECT 992101, 992101, id, 'rolled-back-member'
                    FROM identity_provider WHERE type = 'GITHUB' AND server_url = 'https://github.com';
                    INSERT INTO workspace (
                        id, account_login, account_type, created_at, display_name, is_publicly_viewable, slug, status
                    ) VALUES (992110, 'rolled-back', 'ORG', now(), 'Rolled back', false, 'rolled-back', 'ACTIVE');
                    INSERT INTO workspace_membership (workspace_id, user_id, role, created_at, hidden)
                    VALUES (992110, 992101, 'MEMBER', now(), false);
                    INSERT INTO activity_event (id, event_key, event_type, occurred_at, workspace_id, ingested_at)
                    VALUES ('00000000-0000-0000-0000-000000992120', 'rolled-back', 'ISSUE_CREATED', now(), 992110,
                        now());
                    """);
            }
            connection.commit();

            liquibase.rollback(REVERSIBLE_CHANGE_SETS, CONTEXTS, LABELS);

            assertThat(columns(connection, """
                    SELECT table_name || '.' || column_name,
                        data_type || coalesce('(' || character_maximum_length || ')', '') || ' '
                            || CASE is_nullable WHEN 'NO' THEN 'NOT NULL' ELSE 'NULL' END
                            || coalesce(' DEFAULT ' || column_default, '')
                    FROM information_schema.columns
                    WHERE table_schema = 'public' AND (table_name, column_name) IN (%s)
                    """.formatted(DROPPED)))
                    .containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                            Map.entry("activity_event.xp", "double precision NOT NULL"),
                            Map.entry("workspace_membership.league_points", "integer NOT NULL"),
                            Map.entry("workspace.leaderboard_notification_enabled", "boolean NULL"),
                            Map.entry("workspace.leaderboard_schedule_day", "integer NULL"),
                            Map.entry("workspace.leaderboard_schedule_time", "character varying(10) NULL"),
                            Map.entry("workspace.leaderboard_enabled", "boolean NOT NULL DEFAULT false"),
                            Map.entry("workspace.progression_enabled", "boolean NOT NULL DEFAULT false"),
                            Map.entry("workspace.leagues_enabled", "boolean NOT NULL DEFAULT false"),
                            Map.entry("workspace.mentor_enabled", "boolean NOT NULL DEFAULT false"),
                            Map.entry("workspace.leaderboard_league_cycle_at", "timestamp with time zone NULL")));
            assertThat(columns(connection, """
                    SELECT 'row', concat_ws(',', e.xp, m.league_points, w.mentor_enabled::text, w.leaderboard_enabled::text)
                    FROM activity_event e
                    JOIN workspace w ON w.id = e.workspace_id
                    JOIN workspace_membership m ON m.workspace_id = w.id
                    WHERE e.event_key = 'rolled-back'
                    """))
                    .as("rows written without the columns read as empty, not as the history they had")
                    .containsExactlyEntriesOf(Map.of("row", "0,0,false,false"));
            assertThat(columns(connection, """
                    SELECT name, (to_regclass('public.' || name) IS NOT NULL)::text FROM (VALUES
                        ('idx_activity_event_leaderboard_covering'), ('idx_activity_event_leaderboard'),
                        ('idx_activity_event_xp_lookup'), ('idx_activity_event_workspace_target')) AS index(name)
                    UNION ALL
                    SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
                    WHERE conrelid = 'public.activity_event'::regclass
                    AND conname = 'chk_activity_event_xp_non_negative'
                    """))
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            "idx_activity_event_leaderboard_covering", "true",
                            "idx_activity_event_leaderboard", "true",
                            "idx_activity_event_xp_lookup", "true",
                            "idx_activity_event_workspace_target", "false",
                            "chk_activity_event_xp_non_negative", "CHECK ((xp >= (0)::double precision))"));
            assertThat(columns(
                            connection,
                            "SELECT indexname, indexdef FROM pg_indexes"
                                    + " WHERE indexname = 'idx_activity_event_leaderboard_covering'"))
                    .containsExactlyEntriesOf(Map.of(
                            "idx_activity_event_leaderboard_covering",
                            "CREATE INDEX idx_activity_event_leaderboard_covering ON public.activity_event USING btree"
                                    + " (workspace_id, occurred_at DESC, actor_id, xp)"));
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
        try (Connection connection = connect(DATABASE)) {
            return columns(connection, query);
        }
    }

    private static Map<String, String> columns(Connection connection, String query) throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        try (var statement = connection.createStatement();
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
