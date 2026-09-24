package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class PracticeReleaseMigrationIntegrationTest {

    private static final PostgreSQLTestContainer.TestDatabase DATABASE =
            PostgreSQLTestContainer.createDatabase("hephaestus_practice_release_migration");

    @Test
    void backfillsExistingDefinitionsAndRevisionsBeforeMakingDeliveryBehaviorRequired() throws Exception {
        try (Connection connection = connection();
                Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE practice (id bigint PRIMARY KEY, slug text NOT NULL, name text NOT NULL)");
            sql.execute("CREATE TABLE practice_revision (id bigint PRIMARY KEY, slug text NOT NULL)");
            sql.execute("CREATE TABLE curated_practice_override (slug text PRIMARY KEY, name text)");
            sql.execute(
                    "INSERT INTO practice VALUES (1, 'describe-what-and-why', 'Description'), (2, 'custom-practice', 'Custom')");
            sql.execute(
                    "INSERT INTO practice_revision VALUES (1, 'ready-and-traceable-handoff'), (2, 'custom-practice')");
            sql.execute(
                    "INSERT INTO curated_practice_override VALUES ('issue-scoped-to-single-concern', 'Issue scope'), ('order-only', NULL)");
        }

        try (Liquibase liquibase = new Liquibase(
                "db/changelog/1790187096652_changelog.xml",
                new ClassLoaderResourceAccessor(),
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection())))) {
            liquibase.update(new Contexts("prod"));
        }

        assertThat(value("SELECT delivery_behavior ->> 'summaryOnly' FROM practice WHERE id = 1"))
                .isEqualTo("true");
        assertThat(value("SELECT delivery_behavior ->> 'summaryOnly' FROM practice WHERE id = 2"))
                .isEqualTo("false");
        assertThat(value("SELECT delivery_behavior ->> 'redundantToSlug' FROM practice_revision WHERE id = 1"))
                .isEqualTo("ships-tests-with-the-change");
        assertThat(
                        value(
                                "SELECT delivery_behavior ->> 'overlapGroup' FROM curated_practice_override WHERE slug = 'issue-scoped-to-single-concern'"))
                .isEqualTo("issue-structure");
        assertThat(value("SELECT delivery_behavior FROM curated_practice_override WHERE slug = 'order-only'"))
                .isNull();
    }

    private static @Nullable String value(String query) throws Exception {
        try (Connection connection = connection();
                Statement sql = connection.createStatement();
                ResultSet rows = sql.executeQuery(query)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
    }
}
