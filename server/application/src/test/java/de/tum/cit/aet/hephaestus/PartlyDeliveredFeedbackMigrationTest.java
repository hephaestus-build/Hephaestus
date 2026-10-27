package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import de.tum.cit.aet.hephaestus.testconfig.SchemaRowSeeder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** The data correction that re-projects feedback earlier releases stored as delivered while a copy had not posted. */
@Tag("database")
class PartlyDeliveredFeedbackMigrationTest {

    private static final String CHANGELOG = "1793058726214_changelog.xml";
    private static final Contexts CONTEXTS = new Contexts("prod");
    private static final LabelExpression LABELS = new LabelExpression();
    private static final Timestamp POSTED = Timestamp.from(Instant.parse("2026-10-20T10:00:00Z"));

    private static final UUID FAILED_NOTE = UUID.fromString("00000000-0000-0000-0000-000000993001");
    private static final UUID PLANNED_NOTE = UUID.fromString("00000000-0000-0000-0000-000000993002");
    private static final UUID ALL_POSTED = UUID.fromString("00000000-0000-0000-0000-000000993003");
    private static final UUID NEVER_POSTED = UUID.fromString("00000000-0000-0000-0000-000000993004");

    @Test
    void shouldReprojectOnlyDeliveredFeedbackWithACopyThatDidNotPost() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("partly_delivered_feedback");
        try (Connection connection = connect(database);
                Liquibase liquibase = liquibase(connection)) {
            int changeSets = migrateToTheChangelog(liquibase);
            seed(connection);
            liquibase.update(changeSets, CONTEXTS, LABELS);

            assertThat(columns(
                            connection,
                            "SELECT id::text, state || ',' || coalesce(stop_reason, 'none') FROM feedback"
                                    + " WHERE id::text LIKE '00000000-0000-0000-0000-000000993%'"))
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            FAILED_NOTE.toString(), "FAILED,none",
                            PLANNED_NOTE.toString(), "UNCONFIRMED,DELIVERY_UNCONFIRMED",
                            ALL_POSTED.toString(), "DELIVERED,none",
                            NEVER_POSTED.toString(), "FAILED,none"));
        }
    }

    @Test
    void shouldStopForTheBackupWhenTheChangelogRollsBack() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("partly_delivered_feedback_rollback");
        try (Connection connection = connect(database);
                Liquibase liquibase = liquibase(connection)) {
            int changeSets = migrateToTheChangelog(liquibase);
            liquibase.update(changeSets, CONTEXTS, LABELS);

            assertThatThrownBy(() -> liquibase.rollback(changeSets, CONTEXTS, LABELS))
                    .hasStackTraceContaining("Restore the verified pre-upgrade backup");
        }
    }

    /** Four pieces on the work, each with a summary and a line note, seeded without the rows they refer to. */
    private static void seed(Connection connection) throws SQLException {
        var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        jdbc.execute("SET session_replication_role = 'replica'");
        var rows = new SchemaRowSeeder(jdbc);
        piece(rows, FAILED_NOTE, "DELIVERED", "POSTED", "FAILED");
        piece(rows, PLANNED_NOTE, "DELIVERED", "POSTED", "PLANNED");
        piece(rows, ALL_POSTED, "DELIVERED", "POSTED", "POSTED");
        piece(rows, NEVER_POSTED, "FAILED", "FAILED", "FAILED");
        jdbc.execute("SET session_replication_role = 'origin'");
        connection.commit();
    }

    private static void piece(SchemaRowSeeder rows, UUID id, String state, String summary, String note) {
        var feedback = new LinkedHashMap<String, Object>();
        feedback.put("id", id);
        feedback.put("workspace_id", 993L);
        feedback.put("destination", "ON_WORK");
        feedback.put("item_id", 993L);
        feedback.put("state", state);
        if (state.equals("DELIVERED")) feedback.put("delivered_at", POSTED);
        rows.insert("feedback", feedback);
        rows.insert("feedback_copy", copy(id, "SUMMARY", 0, summary));
        var line = copy(id, "LINE_NOTE", 1, note);
        line.put("path", "src/Main.java");
        line.put("start_line", 3);
        line.put("end_line", 3);
        rows.insert("feedback_copy", line);
    }

    private static Map<String, Object> copy(UUID feedbackId, String kind, int position, String state) {
        var copy = new LinkedHashMap<String, Object>();
        copy.put("id", UUID.randomUUID());
        copy.put("workspace_id", 993L);
        copy.put("feedback_id", feedbackId);
        copy.put("kind", kind);
        copy.put("position", position);
        copy.put("state", state);
        if (state.equals("POSTED")) copy.put("posted_at", POSTED);
        return copy;
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
