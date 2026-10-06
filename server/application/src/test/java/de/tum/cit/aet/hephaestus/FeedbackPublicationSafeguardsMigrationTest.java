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
import java.util.stream.Collectors;
import java.util.stream.Stream;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.PreconditionFailedException;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class FeedbackPublicationSafeguardsMigrationTest {

    private static final String CHANGELOG = "db/changelog/1791246816770_changelog.xml";

    private static final List<String> OLD_REASONS =
            List.of("VOLUME_CAPPED", "OBSERVATION_INVALIDATED", "REPEATS_DELIVERED_NOTE");

    /** The placements the schema admitted before this migration. */
    private static final List<String> OLD_PLACEMENTS = List.of("SUMMARY", "INLINE", "CONVERSATION_TURN");

    @Test
    void shouldKeepEveryRecordedRowAndAdmitBothNewValuesWhenTheOldConstraintsAreMigrated() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("feedback_publication_safeguards_migration");
        createOldSchema(database);
        insertOldRows(database);
        assertRejected(database, "INSERT INTO feedback (suppression_reason) VALUES ('REVIEWED_REVISION_CHANGED')");
        assertRejected(database, "INSERT INTO feedback_placement (placement_type) VALUES ('LOCATION_COMMENT')");

        migrate(database);

        List<@Nullable String> reasons = new ArrayList<>(OLD_REASONS);
        reasons.add(null);
        assertThat(column(database, "SELECT suppression_reason FROM feedback ORDER BY id"))
                .containsExactlyElementsOf(reasons);
        assertThat(column(database, "SELECT placement_type FROM feedback_placement ORDER BY id"))
                .containsExactlyElementsOf(OLD_PLACEMENTS);
        execute(database, "INSERT INTO feedback (suppression_reason) VALUES ('REVIEWED_REVISION_CHANGED')");
        execute(database, "INSERT INTO feedback_placement (placement_type) VALUES ('LOCATION_COMMENT')");
        assertRejected(database, "INSERT INTO feedback (suppression_reason) VALUES ('REVIEWED_REVISION_UNKNOWN')");
        assertRejected(database, "INSERT INTO feedback_placement (placement_type) VALUES ('ANYWHERE')");
    }

    @Test
    void shouldHaltBeforeChangingAnythingWhenOneExpectedConstraintIsMissing() throws Exception {
        TestDatabase database =
                PostgreSQLTestContainer.createDatabase("feedback_publication_safeguards_migration_halt");
        createOldSchema(database);
        insertOldRows(database);
        execute(database, "ALTER TABLE feedback_placement DROP CONSTRAINT chk_feedback_placement_placement");

        assertThatThrownBy(() -> migrate(database)).hasRootCauseInstanceOf(PreconditionFailedException.class);

        assertRejected(database, "INSERT INTO feedback (suppression_reason) VALUES ('REVIEWED_REVISION_CHANGED')");
        assertThat(column(database, "SELECT count(*) FROM feedback")).containsExactly("4");
        assertThat(column(database, "SELECT count(*) FROM feedback_placement")).containsExactly("3");
        assertThat(column(
                        database,
                        "SELECT count(*) FROM pg_constraint WHERE conname = 'chk_feedback_placement_placement'"))
                .containsExactly("0");
    }

    /** Only the columns and constraints this migration replaces, as the schema defined them before it. */
    private static void createOldSchema(TestDatabase database) throws Exception {
        execute(database, """
            CREATE TABLE feedback (
                id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                suppression_reason varchar(48),
                CONSTRAINT chk_feedback_suppression_reason
                    CHECK (suppression_reason IS NULL))
            """);
        migrate(database, "db/changelog/1790510734717_changelog.xml");
        execute(database, """
            CREATE TABLE feedback_placement (
                id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                placement_type varchar(32) NOT NULL,
                CONSTRAINT chk_feedback_placement_placement CHECK (placement_type IN (%s)))
            """.formatted(quoted(OLD_PLACEMENTS)));
    }

    private static void insertOldRows(TestDatabase database) throws SQLException {
        String reasons = Stream.concat(OLD_REASONS.stream().map(reason -> "('" + reason + "')"), Stream.of("(NULL)"))
                .collect(Collectors.joining(", "));
        execute(database, "INSERT INTO feedback (suppression_reason) VALUES " + reasons);
        execute(
                database,
                "INSERT INTO feedback_placement (placement_type) VALUES "
                        + OLD_PLACEMENTS.stream()
                                .map(placement -> "('" + placement + "')")
                                .collect(Collectors.joining(", ")));
    }

    private static String quoted(List<String> values) {
        return values.stream().map(value -> "'" + value + "'").collect(Collectors.joining(", "));
    }

    private static void assertRejected(TestDatabase database, String sql) {
        assertThatThrownBy(() -> execute(database, sql))
                .isInstanceOfSatisfying(
                        SQLException.class,
                        error -> assertThat(error.getSQLState()).isEqualTo("23514"));
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

    private static List<@Nullable String> column(TestDatabase database, String sql) throws SQLException {
        try (Connection connection = connect(database);
                var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            List<@Nullable String> values = new ArrayList<>();
            while (rows.next()) values.add(rows.getString(1));
            return values;
        }
    }

    private static void migrate(TestDatabase database) throws Exception {
        migrate(database, CHANGELOG);
    }

    private static void migrate(TestDatabase database, String changelog) throws Exception {
        var liquibaseDatabase =
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connect(database)));
        try (Liquibase liquibase = new Liquibase(changelog, new ClassLoaderResourceAccessor(), liquibaseDatabase)) {
            liquibase.update(new Contexts());
        }
    }
}
