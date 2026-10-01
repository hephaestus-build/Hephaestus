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
 * The changelog that stores GitHub team review requests, GitLab reviewer states and when a pull request's review
 * requests were received, applied to a database that holds review requests from before it.
 */
@Tag("database")
class ReviewRequestStateMigrationTest {

    private static final String CHANGELOG = "1790548011929_changelog.xml";
    private static final Contexts CONTEXTS = new Contexts("prod");
    private static final LabelExpression LABELS = new LabelExpression();
    private static final TestDatabase DATABASE =
            PostgreSQLTestContainer.createDatabase("review_request_state_migration");

    /** A pull request asking one person for a review, stored before the changelog. */
    private static final String SEED = """
            INSERT INTO "user" (id, native_id, provider_id, login)
            SELECT 993001, 993001, id, 'asked-before'
            FROM identity_provider WHERE type = 'GITHUB' AND server_url = 'https://github.com';
            INSERT INTO issue (id, issue_type, native_id, provider_id, number, comments_count, is_locked)
            SELECT 993010, 'PULL_REQUEST', 993010, id, 1, 0, false
            FROM identity_provider WHERE type = 'GITHUB' AND server_url = 'https://github.com';
            INSERT INTO pull_request_requested_reviewers (pull_request_id, user_id) VALUES (993010, 993001);
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
    void shouldKeepAReviewRequestFromBeforeWithNoStateAndNoDate() throws SQLException {
        assertThat(column("""
                SELECT r.user_id::text, concat_ws(',', coalesce(r.review_state, 'no state'),
                    coalesce(i.reviewers_observed_at::text, 'no date'))
                FROM pull_request_requested_reviewers r JOIN issue i ON i.id = r.pull_request_id
                WHERE r.pull_request_id = 993010
                """)).containsExactlyEntriesOf(Map.of("993001", "no state,no date"));
    }

    @Test
    void shouldIndexTheRequestedTeamWhenTheChangelogIsApplied() throws SQLException {
        assertThat(column("""
                SELECT a.attname, (i.indkey[0] = a.attnum)::text FROM pg_index i
                JOIN pg_class c ON c.oid = i.indexrelid
                JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attname = 'team_id'
                WHERE c.relname = 'idx_pull_request_requested_team_team'
                """)).containsExactlyEntriesOf(Map.of("team_id", "true"));
    }

    /**
     * The team requests of a member's teams ({@code WorkItemQueryRepository.findTeamReviewRequests}) are found by
     * {@code team_id}, which the primary key, led by {@code pull_request_id}, cannot serve. A few test rows make any
     * plan a sequential scan, so this turns that off and asserts the index is the one that answers the lookup.
     */
    @Test
    void shouldFindATeamsReviewRequestsThroughTheTeamIndex() throws SQLException {
        try (Connection connection = connect(DATABASE);
                var statement = connection.createStatement()) {
            statement.execute("SET enable_seqscan = off");
            StringBuilder plan = new StringBuilder();
            try (var rows = statement.executeQuery(
                    "EXPLAIN SELECT pull_request_id FROM pull_request_requested_team WHERE team_id IN (993020, 993021)")) {
                while (rows.next()) {
                    plan.append(rows.getString(1)).append('\n');
                }
            }

            assertThat(plan.toString()).contains("idx_pull_request_requested_team_team");
        }
    }

    @Test
    void shouldRemoveATeamsReviewRequestsWhenTheTeamIsDeleted() throws SQLException {
        try (Connection connection = connect(DATABASE);
                var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO team (id, native_id, provider_id, name)
                    SELECT 993020, 993020, id, 'reviewers'
                    FROM identity_provider WHERE type = 'GITHUB' AND server_url = 'https://github.com';
                    INSERT INTO pull_request_requested_team (pull_request_id, team_id) VALUES (993010, 993020);
                    DELETE FROM team WHERE id = 993020;
                    """);
        }

        assertThat(column("SELECT team_id::text, pull_request_id::text FROM pull_request_requested_team"
                        + " WHERE pull_request_id = 993010"))
                .isEmpty();
    }

    @Test
    void shouldHaltWhenTheTableItCreatesIsAlreadyThere() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("review_request_state_halt");
        try (Connection connection = connect(database);
                Liquibase liquibase = liquibase(connection)) {
            int changeSets = migrateToTheChangelog(liquibase);
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE pull_request_requested_team (pull_request_id BIGINT)");
            }
            connection.commit();

            assertThatThrownBy(() -> liquibase.update(changeSets, CONTEXTS, LABELS))
                    .hasStackTraceContaining("pull_request_requested_team already exists");
        }
    }

    @Test
    void shouldRemoveWhatItAddedAndKeepTheRequestsFromBeforeWhenTheChangelogRollsBack() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("review_request_state_rollback");
        try (Connection connection = connect(database);
                Liquibase liquibase = liquibase(connection)) {
            int changeSets = migrateToTheChangelog(liquibase);
            try (var statement = connection.createStatement()) {
                statement.execute(SEED);
            }
            connection.commit();
            liquibase.update(changeSets, CONTEXTS, LABELS);

            liquibase.rollback(changeSets, CONTEXTS, LABELS);

            assertThat(columns(connection, """
                    SELECT name, coalesce(to_regclass('public.' || name)::text, 'gone') FROM (VALUES
                        ('pull_request_requested_team'), ('idx_pull_request_requested_team_team')) AS object(name)
                    """))
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            "pull_request_requested_team", "gone",
                            "idx_pull_request_requested_team_team", "gone"));
            assertThat(columns(connection, """
                    SELECT table_name, column_name FROM information_schema.columns
                    WHERE table_schema = 'public' AND (table_name, column_name) IN (
                        ('pull_request_requested_reviewers', 'review_state'), ('issue', 'reviewers_observed_at'))
                    """)).isEmpty();
            assertThat(columns(
                            connection,
                            "SELECT user_id::text, pull_request_id::text"
                                    + " FROM pull_request_requested_reviewers WHERE pull_request_id = 993010"))
                    .containsExactlyEntriesOf(Map.of("993001", "993010"));
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
