package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A practice row may not show guidance that its current revision did not record, and the rollback returns the check
 * to the columns it compared before.
 */
@Tag("database")
class PracticeGuidanceProjectionMigrationLiquibaseTest {

    private static final String CHANGELOG = "db/changelog/1791488096611_changelog.xml";
    private static final TestDatabase DATABASE =
            PostgreSQLTestContainer.createDatabase("practice_guidance_projection_migration_test");

    @Test
    void shouldHoldVisualAndGuideToTheCurrentRevisionAcrossRollbackAndReapply() throws Exception {
        createPreMigrationSchema();
        update();
        seedPracticeWithGuidance();

        assertThatThrownBy(() -> execute("UPDATE practice SET visual = NULL WHERE id = 992003"))
                .isInstanceOfSatisfying(
                        SQLException.class,
                        exception -> assertThat(exception.getSQLState()).isEqualTo("23514"));
        assertThatThrownBy(() -> execute("UPDATE practice SET guide = NULL WHERE id = 992003"))
                .isInstanceOfSatisfying(
                        SQLException.class,
                        exception -> assertThat(exception.getSQLState()).isEqualTo("23514"));

        try (Liquibase liquibase = liquibase()) {
            liquibase.rollback(2, "");
        }
        assertThatCode(() -> execute("UPDATE practice SET visual = NULL WHERE id = 992003"))
                .doesNotThrowAnyException();
        execute("UPDATE practice_revision SET visual = NULL WHERE id = 992004");

        update();
        assertThatThrownBy(() -> execute("UPDATE practice SET guide = NULL WHERE id = 992003"))
                .isInstanceOfSatisfying(
                        SQLException.class,
                        exception -> assertThat(exception.getSQLState()).isEqualTo("23514"));
    }

    /** The tables as the previous release left them, with only the columns the projection check reads. */
    private static void createPreMigrationSchema() throws SQLException {
        execute("""
                CREATE TABLE practice_group (id bigint PRIMARY KEY, slug text NOT NULL)
                """, """
                CREATE TABLE curated_practice_override (id bigint PRIMARY KEY)
                """, """
                CREATE TABLE practice (
                    id bigint PRIMARY KEY,
                    current_revision_id bigint,
                    practice_group_id bigint,
                    slug text NOT NULL,
                    name text NOT NULL,
                    applies_to text NOT NULL,
                    signals jsonb NOT NULL,
                    evidence_requirements jsonb NOT NULL,
                    review_when jsonb NOT NULL,
                    subject varchar(16) NOT NULL,
                    precondition jsonb,
                    criteria text NOT NULL,
                    precompute_script text,
                    automated_review_policy jsonb NOT NULL,
                    delivery_behavior jsonb NOT NULL,
                    why_it_matters text,
                    what_good_looks_like text
                )
                """, """
                CREATE TABLE practice_revision (
                    id bigint PRIMARY KEY,
                    practice_id bigint NOT NULL,
                    revision_number integer NOT NULL,
                    slug text,
                    name text,
                    applies_to text,
                    signals jsonb,
                    evidence_requirements jsonb,
                    review_when jsonb,
                    subject varchar(16),
                    precondition jsonb,
                    criteria text NOT NULL,
                    precompute_script text,
                    automated_review_policy jsonb,
                    delivery_behavior jsonb,
                    why_it_matters text,
                    what_good_looks_like text,
                    group_slug text
                )
                """, """
                CREATE FUNCTION enforce_practice_current_revision_projection() RETURNS trigger
                LANGUAGE plpgsql
                AS $current_projection$
                BEGIN
                    RETURN NULL;
                END;
                $current_projection$
                """, """
                CREATE CONSTRAINT TRIGGER practice_requires_current_revision_projection
                    AFTER INSERT OR UPDATE ON practice
                    DEFERRABLE INITIALLY DEFERRED
                    FOR EACH ROW EXECUTE FUNCTION enforce_practice_current_revision_projection()
                """);
    }

    private static void seedPracticeWithGuidance() throws SQLException {
        String visual = "'{\"svg\":\"<svg xmlns=\\\"http://www.w3.org/2000/svg\\\" viewBox=\\\"0 0 1 1\\\"/>\","
                + "\"alt\":\"A square\"}'::jsonb";
        String guide = "'{\"markdown\":\"## How to do it\",\"figures\":{}}'::jsonb";
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("INSERT INTO practice_group (id, slug) VALUES (992002, 'quality')");
            statement.execute("""
                    INSERT INTO practice (
                        id, practice_group_id, slug, name, applies_to, signals, evidence_requirements, review_when,
                        subject, criteria, automated_review_policy, delivery_behavior, visual, guide
                    ) VALUES (
                        992003, 992002, 'guided', 'Guided practice', 'scm.pull_request', '[]'::jsonb, '[]'::jsonb,
                        '{}'::jsonb, 'AUTHOR', 'criteria', '{}'::jsonb, '{}'::jsonb, %s, %s
                    )
                    """.formatted(visual, guide));
            statement.execute("""
                    INSERT INTO practice_revision (
                        id, practice_id, revision_number, slug, name, applies_to, signals, evidence_requirements,
                        review_when, subject, criteria, automated_review_policy, delivery_behavior, group_slug,
                        visual, guide
                    ) VALUES (
                        992004, 992003, 1, 'guided', 'Guided practice', 'scm.pull_request', '[]'::jsonb,
                        '[]'::jsonb, '{}'::jsonb, 'AUTHOR', 'criteria', '{}'::jsonb, '{}'::jsonb, 'quality', %s, %s
                    )
                    """.formatted(visual, guide));
            statement.execute("UPDATE practice SET current_revision_id = 992004 WHERE id = 992003");
            connection.commit();
        }
    }

    private static void update() throws Exception {
        try (Liquibase liquibase = liquibase()) {
            liquibase.update(new Contexts());
        }
    }

    private static Liquibase liquibase() throws Exception {
        var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connect()));
        return new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
    }

    private static void execute(String... statements) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }
}
