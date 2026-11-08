package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DataIntegrityViolationException;

@Tag("integration")
class RepositoryMonitorNamesMigrationIntegrationTest {
    private static final String CONSTRAINT = "uk_repository_to_monitor_workspace_name";

    @Test
    void shouldPreserveCrossWorkspaceAndCaseDistinctMonitorsWhenNamesAreUnique() throws Exception {
        var database = fixture();
        PracticeMigrationFixture.execute(database, """
            INSERT INTO repository_to_monitor VALUES (11,1,101,'org/repo',17,'["dist/**"]'),
                (12,2,101,'org/repo',29,'[]');
            """);
        migrate(database, false);
        PracticeMigrationFixture.execute(
                database, "INSERT INTO repository_to_monitor VALUES(14,1,103,'org/Repo',31,'[]')");

        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT id||':'||checkpoint||':'||generated_paths FROM repository_to_monitor ORDER BY id"))
                .containsExactly("11:17:[\"dist/**\"]", "12:29:[]", "14:31:[]");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                        database, "INSERT INTO repository_to_monitor VALUES(13,1,102,'org/repo',0,'[]')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(CONSTRAINT);
        migrate(database, false);
    }

    @Test
    void shouldPreserveEveryMonitorWhenDuplicateNamesStopTheMigration() throws Exception {
        var database = fixture();
        PracticeMigrationFixture.execute(database, """
            INSERT INTO repository_to_monitor VALUES (11,1,101,'org/repo',17,'["dist/**"]'),
                (12,1,102,'org/repo',29,'["generated/**"]');
            """);

        assertThatThrownBy(() -> migrate(database, false))
                .hasMessageContaining("Repository monitor names are duplicated within a workspace");

        assertThat(
                        PracticeMigrationFixture.column(
                                database,
                                "SELECT id||':'||native_id||':'||checkpoint||':'||generated_paths FROM repository_to_monitor ORDER BY id"))
                .containsExactly("11:101:17:[\"dist/**\"]", "12:102:29:[\"generated/**\"]");
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT conname FROM pg_constraint WHERE conname='" + CONSTRAINT + "'"))
                .isEmpty();
    }

    @Test
    void shouldKeepOneCompleteMonitorWhenConcurrentWritersUseTheSameName() throws Exception {
        var database = fixture();
        migrate(database, false);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> insert(database, 11, ready, start));
            var second = executor.submit(() -> insert(database, 12, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(new boolean[] {first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)})
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            start.countDown();
        }
        assertThat(
                        PracticeMigrationFixture.column(
                                database,
                                "SELECT id::text FROM repository_to_monitor WHERE workspace_id=1 AND name_with_owner='org/repo'"))
                .hasSize(1);
    }

    @Test
    void shouldPreserveEveryMonitorWhenTheConstraintIsRolledBack() throws Exception {
        var database = fixture();
        PracticeMigrationFixture.execute(
                database, "INSERT INTO repository_to_monitor VALUES(11,1,101,'org/repo',17,'[\"dist/**\"]')");
        migrate(database, false);

        migrate(database, true);

        PracticeMigrationFixture.execute(
                database, "INSERT INTO repository_to_monitor VALUES(12,1,102,'org/repo',29,'[]')");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT id||':'||checkpoint||':'||generated_paths FROM repository_to_monitor ORDER BY id"))
                .containsExactly("11:17:[\"dist/**\"]", "12:29:[]");
    }

    private static boolean insert(TestDatabase database, long id, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Writers did not start");
        try {
            PracticeMigrationFixture.execute(
                    database,
                    "INSERT INTO repository_to_monitor VALUES(" + id + ",1," + (id + 100)
                            + ",'org/repo',17,'[\"dist/**\"]')");
            return true;
        } catch (DataIntegrityViolationException exception) {
            assertThat(exception).hasMessageContaining(CONSTRAINT);
            return false;
        }
    }

    private static TestDatabase fixture() {
        var database = PostgreSQLTestContainer.createDatabase(
                "monitor_names_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.execute(database, """
            CREATE TABLE repository_to_monitor(id bigint PRIMARY KEY, workspace_id bigint NOT NULL,
                native_id bigint NOT NULL, name_with_owner text NOT NULL, checkpoint int NOT NULL,
                generated_paths jsonb NOT NULL, UNIQUE(workspace_id,native_id));
            """);
        return database;
    }

    private static void migrate(TestDatabase database, boolean rollback) throws Exception {
        var files = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/changelog/next/*-db2-unique-repository-monitors.xml");
        assertThat(files).hasSize(1);
        String changelog = "db/changelog/next/" + Objects.requireNonNull(files[0].getFilename());
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        changelog,
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            if (rollback) liquibase.rollback(1, new Contexts("prod"), new LabelExpression());
            else liquibase.update(new Contexts("prod"), new LabelExpression());
        }
    }
}
