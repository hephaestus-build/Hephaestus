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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.PreconditionFailedException;
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
    private static final UUID WRITE_STARTED = UUID.fromString("00000000-0000-0000-0000-000000993005");
    private static final UUID PENDING = UUID.fromString("00000000-0000-0000-0000-000000993006");
    private static final UUID WITHDRAWN = UUID.fromString("00000000-0000-0000-0000-000000993007");
    private static final UUID OTHER_WORKSPACE_COPY = UUID.fromString("00000000-0000-0000-0000-000000993009");

    private static final UUID SENDING = UUID.fromString("00000000-0000-0000-0000-000000993010");
    private static final UUID UNCERTAIN = UUID.fromString("00000000-0000-0000-0000-000000993011");
    private static final UUID RESTORED = UUID.fromString("00000000-0000-0000-0000-000000993012");
    private static final UUID TIED_RESTORE = UUID.fromString("00000000-0000-0000-0000-000000993013");
    private static final UUID STARTED_AND_FAILED = UUID.fromString("00000000-0000-0000-0000-000000993014");

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
                    .containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                            Map.entry(FAILED_NOTE.toString(), "FAILED,none"),
                            Map.entry(PLANNED_NOTE.toString(), "FAILED,none"),
                            Map.entry(ALL_POSTED.toString(), "DELIVERED,none"),
                            Map.entry(NEVER_POSTED.toString(), "FAILED,none"),
                            Map.entry(WRITE_STARTED.toString(), "UNCONFIRMED,DELIVERY_UNCONFIRMED"),
                            Map.entry(PENDING.toString(), "DELIVERED,none"),
                            Map.entry(SENDING.toString(), "DELIVERED,none"),
                            Map.entry(UNCERTAIN.toString(), "DELIVERED,none"),
                            Map.entry(WITHDRAWN.toString(), "UNCONFIRMED,DELIVERY_UNCONFIRMED"),
                            Map.entry(RESTORED.toString(), "FAILED,none"),
                            Map.entry(TIED_RESTORE.toString(), "FAILED,none"),
                            Map.entry(STARTED_AND_FAILED.toString(), "UNCONFIRMED,DELIVERY_UNCONFIRMED"),
                            Map.entry(OTHER_WORKSPACE_COPY.toString(), "DELIVERED,none")));

            assertThat(columns(
                            connection,
                            "SELECT id::text, (delivered_at=TIMESTAMPTZ '2026-10-20 10:00:00+00')::text FROM feedback"
                                    + " WHERE id::text LIKE '00000000-0000-0000-0000-000000993%'"
                                    + " AND id<>'" + NEVER_POSTED + "'"))
                    .hasSize(12)
                    .allSatisfy((id, unchanged) -> assertThat(unchanged)
                            .as("first appearance of %s", id)
                            .isEqualTo("true"));
            // A copy nothing would send fails as absent, so a retry reaches it; a withdrawn piece keeps its own.
            assertThat(columns(
                            connection,
                            "SELECT feedback_id::text, state || ',' || coalesce(readback, 'none') FROM feedback_copy"
                                    + " WHERE kind = 'LINE_NOTE' AND feedback_id IN ('" + PLANNED_NOTE + "', '"
                                    + WITHDRAWN + "')"))
                    .containsExactlyInAnyOrderEntriesOf(
                            Map.of(PLANNED_NOTE.toString(), "FAILED,ABSENT", WITHDRAWN.toString(), "PLANNED,none"));
        }
    }

    @Test
    void shouldHaltWithoutAColumnTheCorrectionReads() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("partly_delivered_feedback_precondition");
        try (Connection connection = connect(database);
                Liquibase liquibase = liquibase(connection)) {
            int changeSets = migrateToTheChangelog(liquibase);
            try (var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE feedback_copy DROP COLUMN last_error");
            }
            connection.commit();
            assertThatThrownBy(() -> liquibase.update(changeSets, CONTEXTS, LABELS))
                    .hasRootCauseInstanceOf(PreconditionFailedException.class)
                    .hasStackTraceContaining("feedback_copy.last_error does not exist");
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

    /** Feedback and its copies, seeded without the rows they refer to. */
    private static void seed(Connection connection) throws SQLException {
        var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        jdbc.execute("SET session_replication_role = 'replica'");
        var rows = new SchemaRowSeeder(jdbc);
        piece(rows, FAILED_NOTE, "DELIVERED", "POSTED", "FAILED", "DONE");
        piece(rows, PLANNED_NOTE, "DELIVERED", "POSTED", "PLANNED", "DONE");
        piece(rows, ALL_POSTED, "DELIVERED", "POSTED", "POSTED", "DONE");
        piece(rows, NEVER_POSTED, "FAILED", "FAILED", "FAILED", "DONE");
        piece(rows, WRITE_STARTED, "DELIVERED", "POSTED", "WRITE_STARTED", "DONE");
        piece(rows, PENDING, "DELIVERED", "POSTED", "FAILED", "PENDING");
        piece(rows, SENDING, "DELIVERED", "POSTED", "FAILED", "SENDING");
        piece(rows, UNCERTAIN, "DELIVERED", "POSTED", "FAILED", "UNCERTAIN");
        piece(rows, WITHDRAWN, "DELIVERED", "POSTED", "PLANNED", "DONE");
        rows.insert(
                "feedback_decision",
                Map.of("id", UUID.randomUUID(), "workspace_id", 993L, "feedback_id", WITHDRAWN, "action", "WITHDRAW"));
        piece(rows, RESTORED, "DELIVERED", "POSTED", "PLANNED", "DONE");
        piece(rows, TIED_RESTORE, "DELIVERED", "POSTED", "PLANNED", "DONE");
        for (var id : List.of(RESTORED, TIED_RESTORE)) {
            rows.insert(
                    "feedback_decision",
                    Map.of(
                            "id",
                            UUID.fromString(
                                    id.equals(RESTORED)
                                            ? "00000000-0000-0000-0000-000000000001"
                                            : "00000000-0000-0000-0000-000000000003"),
                            "workspace_id",
                            993L,
                            "feedback_id",
                            id,
                            "action",
                            "WITHDRAW",
                            "decided_at",
                            POSTED));
            rows.insert(
                    "feedback_decision",
                    Map.of(
                            "id",
                            UUID.fromString(
                                    id.equals(RESTORED)
                                            ? "00000000-0000-0000-0000-000000000002"
                                            : "00000000-0000-0000-0000-000000000004"),
                            "workspace_id",
                            993L,
                            "feedback_id",
                            id,
                            "action",
                            "RESTORE",
                            "decided_at",
                            id.equals(RESTORED)
                                    ? Timestamp.from(POSTED.toInstant().plusSeconds(1))
                                    : POSTED));
        }
        piece(rows, STARTED_AND_FAILED, "DELIVERED", "WRITE_STARTED", "FAILED", "DONE");
        piece(rows, OTHER_WORKSPACE_COPY, "DELIVERED", "POSTED", "POSTED", "DONE");
        var foreign = copy(OTHER_WORKSPACE_COPY, "SUMMARY", 5, "FAILED");
        foreign.put("workspace_id", 994L);
        rows.insert("feedback_copy", foreign);
        jdbc.execute("SET session_replication_role = 'origin'");
        connection.commit();
    }

    private static void piece(
            SchemaRowSeeder rows, UUID id, String state, String first, String second, String send) {
        var feedback = new LinkedHashMap<String, Object>();
        feedback.put("id", id);
        feedback.put("workspace_id", 993L);
        feedback.put("destination", "ON_WORK");
        feedback.put("item_id", 993L);
        feedback.put("state", state);
        if (state.equals("DELIVERED")) feedback.put("delivered_at", POSTED);
        rows.insert("feedback", feedback);
        rows.insert("feedback_copy", copy(id, "SUMMARY", 0, first));
        var other = copy(id, "LINE_NOTE", 1, second);
        other.put("path", "src/Main.java");
        other.put("start_line", 3);
        other.put("end_line", 3);
        rows.insert("feedback_copy", other);
        var sending = new LinkedHashMap<String, Object>();
        sending.put("feedback_id", id);
        sending.put("workspace_id", 993L);
        sending.put("action", "POST");
        sending.put("state", send);
        if (send.equals("SENDING")) {
            sending.put("lease_owner", "migration-test");
            sending.put("lease_expires_at", POSTED);
        }
        rows.insert("feedback_send", sending);
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
        if (state.equals("WRITE_STARTED")) copy.put("write_started_at", POSTED);
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
