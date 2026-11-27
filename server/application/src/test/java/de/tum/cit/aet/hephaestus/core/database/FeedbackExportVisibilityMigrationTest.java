package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.DriverManager;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class FeedbackExportVisibilityMigrationTest {
    private static final String CHANGELOG = "-refactor-5264-share-export-feedback-states.xml";
    private static final String TRIGGER = """
            SELECT pg_get_triggerdef(oid) FROM pg_trigger WHERE tgrelid = 'public.feedback'::regclass
              AND tgname = 'trg_feedback_changed_exports'
            """;

    @Test
    void shouldRestoreTheOriginalTriggerAndRemoveTheSharedPredicateOnRollback() throws Exception {
        var name = "feedback_export_" + UUID.randomUUID().toString().replace("-", "");
        var database = PostgreSQLTestContainer.createDatabase(name);
        PostgreSQLTestContainer.applyDatabaseRoles(name);
        var owner = PostgreSQLTestContainer.asOwner(database);
        PracticeMigrationFixture.upgradeBefore(owner, "prod", CHANGELOG);
        var before = PracticeMigrationFixture.column(owner, TRIGGER);
        assertThat(before).hasSize(1);

        try (var connection = DriverManager.getConnection(owner.jdbcUrl(), owner.username(), owner.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG);
            assertThat(PracticeMigrationFixture.column(owner, TRIGGER)).isNotEqualTo(before);
            PracticeMigrationFixture.rollbackOnly(liquibase, "prod", CHANGELOG);
        }

        assertThat(PracticeMigrationFixture.column(owner, TRIGGER)).isEqualTo(before);
        assertThat(PracticeMigrationFixture.column(owner, """
                SELECT (to_regprocedure('public.account_export_feedback_state_visible(character varying)') IS NULL)::text
                """)).containsExactly("true");
    }
}
