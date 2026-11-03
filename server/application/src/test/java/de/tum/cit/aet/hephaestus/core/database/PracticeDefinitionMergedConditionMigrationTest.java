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
 * The database decides whether a practice's current definition is complete. A bundled definition that it refuses
 * surfaces only when a workspace installs the catalog, so every bundled definition is held to it here.
 */
@Tag("database")
class PracticeDefinitionMergedConditionMigrationTest {

    private static final String CHANGELOG = "practice-definition-merged.xml";
    private static final String MERGE_GATED = "merged-past-unresolved-review-threads";
    private static final Contexts CONTEXTS = new Contexts("prod");
    private static final LabelExpression LABELS = new LabelExpression();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TestDatabase DATABASE = PostgreSQLTestContainer.createDatabase("practice_definition_merged");

    @Test
    void shouldCompleteEveryBundledDefinitionAndOnlyATrueMergedConditionUntilTheChangelogRollsBack() throws Exception {
        try (Connection connection = connect();
                Liquibase liquibase = liquibase(connection)) {
            var unrun = liquibase.listUnrunChangeSets(CONTEXTS, LABELS);
            int before = 0;
            while (!unrun.get(before).getFilePath().endsWith(CHANGELOG)) before++;
            liquibase.update(before, CONTEXTS, LABELS);
            assertThat(complete(definition(MERGE_GATED))).isFalse();

            liquibase.update(1, CONTEXTS, LABELS);

            assertThat(ScmCaptureFixtures.bundledPractices().keySet())
                    .isNotEmpty()
                    .allSatisfy(slug -> assertThat(complete(definition(slug)))
                            .as("%s", slug)
                            .isTrue());
            assertThat(complete(withClause("{\"merged\":false}"))).isFalse();
            assertThat(complete(withClause("{\"merged\":\"true\"}"))).isFalse();
            assertThat(complete(withClause("{\"merged\":true,\"hasItems\":\"scm.review-threads\"}")))
                    .isFalse();

            liquibase.rollback(1, CONTEXTS, LABELS);

            assertThat(complete(definition(MERGE_GATED))).isFalse();
        }
    }

    private static String definition(String slug) {
        return MAPPER.writeValueAsString(
                Objects.requireNonNull(ScmCaptureFixtures.bundledPractices().get(slug), slug)
                        .definition());
    }

    private static String withClause(String clause) {
        var definition = (ObjectNode) MAPPER.readTree(definition(MERGE_GATED));
        var appliesWhen = (ObjectNode) definition.path("appliesWhen");
        appliesWhen.putArray("anyOf").add(MAPPER.readTree(clause));
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
