package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCaptureFixtures;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A practice that people or nobody review keeps its scan settings, unused, so that choosing Hephaestus again restores
 * them. Before the changelog the database refused such a definition, which forced every save to drop the scan.
 */
@Tag("database")
class ReviewerKeepsReviewSettingsMigrationTest {

    private static final String CHANGELOG = "fix-reviewer-keeps-review-settings.xml";
    private static final String SCANNED = "keeps-functions-small-and-single-purpose";
    private static final Contexts CONTEXTS = new Contexts("prod");
    private static final LabelExpression LABELS = new LabelExpression();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TestDatabase DATABASE = PostgreSQLTestContainer.createDatabase("reviewer_keeps_settings");

    @Test
    void shouldKeepTheScanOfAPracticeThatNobodyReviewsUntilTheChangelogRollsBack() throws Exception {
        try (Connection connection = connect();
                Liquibase liquibase = liquibase(connection)) {
            var unrun = liquibase.listUnrunChangeSets(CONTEXTS, LABELS);
            int before = 0;
            while (!unrun.get(before).getFilePath().endsWith(CHANGELOG)) before++;
            liquibase.update(before, CONTEXTS, LABELS);
            assertThat(complete(reviewedBy("HEPHAESTUS"))).isTrue();
            assertThat(complete(reviewedBy("NOBODY"))).isFalse();

            liquibase.update(1, CONTEXTS, LABELS);

            assertThat(complete(reviewedBy("NOBODY"))).isTrue();
            assertThat(complete(reviewedBy("HEPHAESTUS"))).isTrue();

            liquibase.rollback(1, CONTEXTS, LABELS);

            assertThat(complete(reviewedBy("NOBODY"))).isFalse();
        }
    }

    /** The bundled definition with a scan and a practice script field, reviewed by {@code by}. */
    private static String reviewedBy(String by) {
        var definition = (ObjectNode) MAPPER.valueToTree(
                Objects.requireNonNull(ScmCaptureFixtures.bundledPractices().get(SCANNED), SCANNED)
                        .definition());
        assertThat(definition.path("scan").isObject()).isTrue();
        ((ObjectNode) definition.path("reviewedBy")).put("by", by);
        return definition.toString();
    }

    private static boolean complete(String definition) {
        try (Connection connection = connect();
                var statement = connection.prepareStatement("SELECT practice_definition_complete(?::jsonb)")) {
            statement.setString(1, definition);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
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

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
    }
}
