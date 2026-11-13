package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.DriverManager;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A local rollback cannot include the later backup-only migration in the same master. */
@Tag("database")
class MigrationFixtureScopeDatabaseTest {
    private static final String OWN = "db/migration-fixture/own.xml";
    private static final String TABLES = "SELECT table_name FROM information_schema.tables WHERE table_schema='public'"
            + " AND table_name IN ('scope_first','scope_second','scope_later') ORDER BY table_name";

    @Test
    void shouldApplyAndRollbackOnlyTheNamedChangelogLeavingTheLaterMigrationPending() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "scope_" + UUID.randomUUID().toString().replace("-", ""));
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/migration-fixture/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            PracticeMigrationFixture.upgradeOnly(liquibase, "prod", OWN);
            assertThat(PracticeMigrationFixture.column(database, TABLES))
                    .containsExactly("scope_first", "scope_second");

            PracticeMigrationFixture.rollbackOnly(liquibase, "prod", OWN);

            assertThat(PracticeMigrationFixture.column(database, TABLES)).isEmpty();
        }
    }

    @Test
    void shouldRefuseRollbackWithoutChangingEitherSchemaWhenALaterMigrationHasRun() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "scope_tail_" + UUID.randomUUID().toString().replace("-", ""));
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/migration-fixture/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            PracticeMigrationFixture.upgradeOnly(liquibase, "prod", OWN);
            liquibase.update(new Contexts("prod"), new LabelExpression());

            assertThatThrownBy(() -> PracticeMigrationFixture.rollbackOnly(liquibase, "prod", OWN))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("later changesets remain");
            assertThat(PracticeMigrationFixture.column(database, TABLES))
                    .containsExactly("scope_first", "scope_later", "scope_second");
        }
    }
}
