package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class ObservationAssessmentMigrationTest {
    private static final TestDatabase DATABASE =
            PostgreSQLTestContainer.createDatabase("observation_assessment_migration");

    @Test
    void shouldPreservePopulatedHistoryAndEnforceEveryAxisCombination() throws Exception {
        execute("""
            CREATE TABLE observation (
                id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                presence varchar(16) NOT NULL,
                assessment varchar(8), severity varchar(16), evidence text,
                CONSTRAINT chk_observation_presence CHECK (presence IN ('PRESENT','ABSENT','NOT_APPLICABLE','INCONCLUSIVE')),
                CONSTRAINT chk_observation_presence_assessment CHECK (
                    (presence IN ('PRESENT','ABSENT') AND assessment IS NOT NULL)
                    OR (presence IN ('NOT_APPLICABLE','INCONCLUSIVE') AND assessment IS NULL)),
                CONSTRAINT chk_observation_assessment CHECK (assessment IN ('GOOD','BAD')),
                CONSTRAINT chk_observation_severity CHECK (severity IN ('INFO','MINOR','MAJOR','CRITICAL'))
            )
            """);
        execute("""
            INSERT INTO observation (presence, assessment, severity, evidence) VALUES
                ('PRESENT','GOOD','INFO','strength'), ('ABSENT','GOOD',NULL,'avoidance'),
                ('PRESENT','BAD','MAJOR','problem'), ('ABSENT','BAD','MINOR','gap'),
                ('NOT_APPLICABLE',NULL,'INFO','no occasion'), ('INCONCLUSIVE',NULL,NULL,'ambiguous'),
                ('PRESENT','BAD',NULL,'must not invent severity')
            """);
        assertThatThrownBy(ObservationAssessmentMigrationTest::migrate)
                .hasRootCauseInstanceOf(liquibase.exception.PreconditionFailedException.class);
        execute("DELETE FROM observation WHERE evidence = 'must not invent severity'");
        migrate();
        try (Connection connection = connect();
                var statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "SELECT assessment_status, presence, assessment, severity, evidence FROM observation ORDER BY id")) {
            String[][] expected = {
                {"ASSESSED", "PRESENT", "GOOD", null, "strength"},
                {"ASSESSED", "ABSENT", "GOOD", null, "avoidance"},
                {"ASSESSED", "PRESENT", "BAD", "MAJOR", "problem"},
                {"ASSESSED", "ABSENT", "BAD", "MINOR", "gap"},
                {"NOT_APPLICABLE", null, null, null, "no occasion"},
                {"UNDETERMINED", null, null, null, "ambiguous"}
            };
            for (String[] row : expected) {
                assertThat(rows.next()).isTrue();
                for (int column = 0; column < row.length; column++)
                    assertThat(rows.getString(column + 1)).isEqualTo(row[column]);
            }
            assertThat(rows.next()).isFalse();
        }
        for (String status : new String[] {"ASSESSED", "NOT_APPLICABLE", "UNDETERMINED", "UNKNOWN", "NULL"}) {
            for (String presence : new String[] {"PRESENT", "ABSENT", "NULL", "INCONCLUSIVE"}) {
                for (String assessment : new String[] {"GOOD", "BAD", "NULL"}) {
                    for (String severity : new String[] {"INFO", "MINOR", "MAJOR", "CRITICAL", "NULL"}) {
                        boolean assessed = status.equals("ASSESSED")
                                && (presence.equals("PRESENT") || presence.equals("ABSENT"))
                                && !assessment.equals("NULL");
                        boolean unassessed = (status.equals("NOT_APPLICABLE") || status.equals("UNDETERMINED"))
                                && presence.equals("NULL")
                                && assessment.equals("NULL");
                        boolean valid =
                                (assessed || unassessed) && (assessment.equals("BAD") != severity.equals("NULL"));
                        String sql = "INSERT INTO observation (assessment_status,presence,assessment,severity) VALUES ("
                                + literal(status) + "," + literal(presence) + "," + literal(assessment) + ","
                                + literal(severity) + ")";
                        if (valid) execute(sql);
                        else assertThatThrownBy(() -> execute(sql)).isInstanceOf(SQLException.class);
                    }
                }
            }
        }
    }

    private static String literal(String value) {
        return value.equals("NULL") ? "NULL" : "'" + value + "'";
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connect();
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void migrate() throws Exception {
        var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connect()));
        try (Liquibase liquibase = new Liquibase(
                "db/changelog/1789123473151_changelog.xml", new ClassLoaderResourceAccessor(), database)) {
            liquibase.update(new Contexts());
        }
    }
}
