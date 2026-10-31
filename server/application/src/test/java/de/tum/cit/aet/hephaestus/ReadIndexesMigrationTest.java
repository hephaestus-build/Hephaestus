package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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
 * The changelog that indexes the completion poll, a developer's newest result and the reviews of an item, and drops
 * three indexes that no read needs. A few test rows make any plan a sequential scan, so the plan tests turn it off and
 * assert which index answers each read.
 */
@Tag("database")
class ReadIndexesMigrationTest {

    private static final String CHANGELOG = "1792928799325_changelog.xml";
    private static final Contexts CONTEXTS = new Contexts("prod");
    private static final LabelExpression LABELS = new LabelExpression();
    private static final TestDatabase DATABASE = PostgreSQLTestContainer.createDatabase("read_indexes_migration");
    private static final List<String> ADDED =
            List.of("idx_review_job_completion", "idx_result_developer", "idx_review_item_item");
    private static final List<String> DROPPED =
            List.of("idx_review_job_processing", "idx_activity_event_workspace_time", "idx_activity_event_actor_time");

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection connection = connect(DATABASE);
                Liquibase liquibase = liquibase(connection)) {
            liquibase.update(CONTEXTS, LABELS);
        }
    }

    /** The server's completion pass ({@code ReviewJobRepository.findTerminalForProcessing}), every 10 seconds. */
    @Test
    void shouldFindTheJobsToCompleteThroughTheCompletionIndex() throws SQLException {
        assertThat(plan("""
                SELECT id FROM review_job WHERE status NOT IN ('QUEUED', 'RUNNING')
                  AND processing_status IN ('NONE', 'PENDING')
                ORDER BY completed_at, id LIMIT 50
                """)).contains("idx_review_job_completion");
    }

    /** {@code ResultRepository.findFirstByWorkspaceIdAndDeveloperIdOrderByRecordedAtDescIdDesc}, ended rows included. */
    @Test
    void shouldFindADevelopersNewestResultThroughTheDeveloperIndex() throws SQLException {
        assertThat(plan("""
                SELECT id FROM result WHERE workspace_id = 1 AND developer_id = 2
                ORDER BY recorded_at DESC, id DESC LIMIT 1
                """)).contains("idx_result_developer");
    }

    /**
     * {@code ItemTraceRepository.reviewsForItem}: the reviews of the item and the reviews that include it, each found by
     * workspace and item, so the read does not walk the workspace's reviews.
     */
    @Test
    void shouldFindTheReviewsOfAnItemThroughTheItemIndexes() throws SQLException {
        assertThat(plan("""
                SELECT q.id FROM review q WHERE q.workspace_id = 1 AND q.id IN (
                  SELECT r.id FROM review r WHERE r.workspace_id = 1 AND r.item_id = 2
                  UNION SELECT m.review_id FROM review_item m WHERE m.workspace_id = 1 AND m.item_id = 2)
                ORDER BY q.ordered_at DESC, q.id::text DESC LIMIT 100
                """)).contains("idx_review_item_item", "idx_review_item ");
    }

    @Test
    void shouldAddTheThreeIndexesAndDropTheThreeItReplaces() throws SQLException {
        assertThat(existing(ADDED)).containsExactlyElementsOf(ADDED);
        assertThat(existing(DROPPED)).isEmpty();
    }

    /** A failed concurrent build leaves an invalid index behind, which the next run replaces. */
    @Test
    void shouldReplaceAnIndexThatAFailedBuildLeftBehind() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("read_indexes_leftover");
        try (Connection connection = connect(database);
                Liquibase liquibase = liquibase(connection)) {
            var pending = liquibase.listUnrunChangeSets(CONTEXTS, LABELS);
            int before = (int) pending.stream()
                    .takeWhile(changeSet -> !changeSet.getFilePath().endsWith(CHANGELOG))
                    .count();
            liquibase.update(before, CONTEXTS, LABELS);
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE INDEX idx_review_job_completion ON review_job (id)");
            }
            connection.commit();

            liquibase.update(CONTEXTS, LABELS);

            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery(
                            "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_review_job_completion'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).contains("(completed_at, id)");
            }
        }
    }

    @Test
    void shouldRestoreTheDroppedIndexesWhenTheChangelogRollsBack() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("read_indexes_rollback");
        try (Connection connection = connect(database);
                Liquibase liquibase = liquibase(connection)) {
            // A rollback by count undoes the newest change sets first. The database stops at this changelog, so a
            // later change set that cannot be undone, such as a dropped table, does not stop the rollback.
            var unrun = liquibase.listUnrunChangeSets(CONTEXTS, LABELS);
            int first = 0;
            while (!unrun.get(first).getFilePath().endsWith(CHANGELOG)) first++;
            int last = first;
            while (last < unrun.size() && unrun.get(last).getFilePath().endsWith(CHANGELOG)) last++;
            liquibase.update(last, CONTEXTS, LABELS);

            liquibase.rollback(last - first, CONTEXTS, LABELS);

            assertThat(existing(connection, ADDED)).isEmpty();
            assertThat(existing(connection, DROPPED)).containsExactlyElementsOf(DROPPED);
        }
    }

    private static String plan(String query) throws SQLException {
        try (Connection connection = connect(DATABASE);
                var statement = connection.createStatement()) {
            statement.execute("SET enable_seqscan = off");
            StringBuilder plan = new StringBuilder();
            try (var rows = statement.executeQuery("EXPLAIN " + query)) {
                while (rows.next()) {
                    plan.append(rows.getString(1)).append('\n');
                }
            }
            return plan.toString();
        }
    }

    private static List<String> existing(List<String> indexes) throws SQLException {
        try (Connection connection = connect(DATABASE)) {
            return existing(connection, indexes);
        }
    }

    /** The given indexes that exist and are valid, in the given order. */
    private static List<String> existing(Connection connection, List<String> indexes) throws SQLException {
        List<String> found = new ArrayList<>();
        for (String index : indexes) {
            try (var statement = connection.prepareStatement(
                    "SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = ? AND i.indisvalid")) {
                statement.setString(1, index);
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        found.add(index);
                    }
                }
            }
        }
        return found;
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

    private static Connection connect(TestDatabase database) throws SQLException {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }
}
