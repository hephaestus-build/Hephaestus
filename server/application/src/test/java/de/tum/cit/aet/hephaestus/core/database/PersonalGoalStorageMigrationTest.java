package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.DriverManager;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class PersonalGoalStorageMigrationTest {
    private static final String CHANGELOG = "-personal-goal-storage.xml";
    private static final String CONTEXT = "prod";

    @Test
    void shouldRollBackOnlyWhilePersonalGoalStorageIsEmpty() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase("personal_goal_storage");
        PracticeMigrationFixture.upgradeBefore(database, CONTEXT, CHANGELOG);
        try (var connection = DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            PracticeMigrationFixture.upgradeOnly(liquibase, CONTEXT, CHANGELOG);
            PracticeMigrationFixture.execute(database, """
                WITH owner AS (
                    INSERT INTO public.account (display_name, status) VALUES ('Goal owner', 'ACTIVE') RETURNING id
                )
                INSERT INTO public.personal_goal (account_id, goal_id, words, revision)
                SELECT id, gen_random_uuid(), 'Give a clear reason for one review comment.', 1 FROM owner;
                """);

            assertThatThrownBy(() -> PracticeMigrationFixture.rollbackOnly(liquibase, CONTEXT, CHANGELOG))
                    .hasStackTraceContaining("Restore the verified pre-upgrade database backup");
            liquibase.getDatabase().rollback();
            assertThat(PracticeMigrationFixture.column(database, "SELECT words FROM public.personal_goal"))
                    .containsExactly("Give a clear reason for one review comment.");

            PracticeMigrationFixture.execute(database, "DELETE FROM public.personal_goal");
            PracticeMigrationFixture.rollbackOnly(liquibase, CONTEXT, CHANGELOG);
            assertThat(PracticeMigrationFixture.column(database, """
                SELECT to_regclass('public.personal_goal') IS NULL
                   AND to_regprocedure('public.personal_goal_export_changed()') IS NULL
                """)).containsExactly("t");
        }
    }
}
