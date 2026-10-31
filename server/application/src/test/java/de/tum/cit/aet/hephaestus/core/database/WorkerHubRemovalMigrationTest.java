package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * The 1.0 changelog that removes the worker hub, applied to a database with review attempts from before it: an attempt
 * still running at the upgrade has no lease and counts one drain, so lease recovery requeues it without a retry. An
 * attempt that already ended counts none.
 */
@Tag("database")
class WorkerHubRemovalMigrationTest {
    private static final String CHANGELOG = "1793149793934_changelog.xml";

    @Test
    void shouldCountOnlyALeaselessClaimedAttemptAsADrainAndDropTheTokenDenylist() throws Exception {
        var database = PracticeMigrationFixture.releasedDatabase("dev");
        PracticeMigrationFixture.upgradeBefore(database, "dev", CHANGELOG);
        seedAttempts(database);

        PracticeMigrationFixture.upgrade(database, "dev");

        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT id || ':' || drain_count FROM review_job WHERE id::text LIKE '%979%' ORDER BY id"))
                .containsExactly(
                        "00000000-0000-0000-0000-000000979001:1",
                        "00000000-0000-0000-0000-000000979002:0",
                        "00000000-0000-0000-0000-000000979003:0",
                        "00000000-0000-0000-0000-000000979004:0",
                        "00000000-0000-0000-0000-000000979005:1");
        assertThat(
                        PracticeMigrationFixture.column(
                                database,
                                "SELECT count(*)::text FROM information_schema.tables WHERE table_name='worker_token_denylist'"))
                .containsExactly("0");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void shouldRefuseEachCutoverRollbackWithoutChangingHistoryOrCountingAnotherDrain(int applied) throws Exception {
        var database = PracticeMigrationFixture.releasedDatabase("dev");
        PracticeMigrationFixture.upgradeBefore(database, "dev", CHANGELOG);
        seedAttempts(database);
        PracticeMigrationFixture.execute(
                database,
                "INSERT INTO worker_token_denylist(jti,revoked_at,expires_at) VALUES ('revoked-before-cutover',now(),now()+interval '1 hour')");
        var source = new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password());
        try (var connection = source.getConnection();
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            liquibase
                    .getDatabaseChangeLog()
                    .getPreconditions()
                    .getNestedPreconditions()
                    .clear();
            var contexts = new Contexts("dev");
            var labels = new LabelExpression();
            liquibase.update(applied, contexts, labels);
            var history = PracticeMigrationFixture.column(
                    database,
                    "SELECT id FROM databasechangelog WHERE filename LIKE '%1793149793934_changelog.xml' ORDER BY orderexecuted");
            var drains = PracticeMigrationFixture.column(
                    database,
                    "SELECT id || ':' || drain_count FROM review_job WHERE id::text LIKE '%979%' ORDER BY id");
            assertThat(history).hasSize(applied);

            assertThatThrownBy(() -> liquibase.rollback(1, contexts, labels))
                    .hasStackTraceContaining("restore the database backup taken before this upgrade");

            assertThat(
                            PracticeMigrationFixture.column(
                                    database,
                                    "SELECT id FROM databasechangelog WHERE filename LIKE '%1793149793934_changelog.xml' ORDER BY orderexecuted"))
                    .containsExactlyElementsOf(history);
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT id || ':' || drain_count FROM review_job WHERE id::text LIKE '%979%' ORDER BY id"))
                    .containsExactlyElementsOf(drains);
            assertThat(
                            PracticeMigrationFixture.column(
                                    database,
                                    "SELECT count(*)::text FROM information_schema.tables WHERE table_name='worker_token_denylist'"))
                    .containsExactly("0");
            liquibase.update(contexts, labels);
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT drain_count::text FROM review_job WHERE id='00000000-0000-0000-0000-000000979001'"))
                    .containsExactly("1");
        }
    }

    private static void seedAttempts(PostgreSQLTestContainer.TestDatabase database) {
        new ResourceDatabasePopulator(new ClassPathResource("db/practice-native/result-parent.sql"))
                .execute(new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password()));
        // One review for each active job: a review has at most one queued or running job.
        PracticeMigrationFixture.execute(database, """
                INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,status,ordered_at)
                SELECT ('00000000-0000-0000-0000-00000097901' || n)::uuid,workspace_id,item_id,kind,
                       'native-v1-' || n,origin,status,now()
                FROM review, generate_series(2,4) AS n
                WHERE id='00000000-0000-0000-0000-000000978011'
                """);
        PracticeMigrationFixture.execute(database, """
                INSERT INTO review_job
                    (id,review_id,workspace_id,submitted_at,status,processing_status,trace_id,input,config_snapshot,
                     attempt,worker_id,leased_until,admission_digest,admission_attempt)
                SELECT job.id::uuid,job.review::uuid,978001,now(),job.status,'NONE',
                       '00000000000000000000000000000001','{}','{}',1,job.worker_id,job.leased_until,job.digest,
                       CASE WHEN job.digest IS NULL THEN NULL ELSE 1 END
                FROM (VALUES
                    -- Claimed by 0.x, which set no lease: one drain.
                    ('00000000-0000-0000-0000-000000979001','00000000-0000-0000-0000-000000978011','RUNNING',
                     'worker-0x',NULL::timestamptz,NULL),
                    -- Already ended: none.
                    ('00000000-0000-0000-0000-000000979002','00000000-0000-0000-0000-000000978011','FAILED',
                     'worker-0x',NULL,NULL),
                    -- Claimed by 1.0, which leases every claim: none.
                    ('00000000-0000-0000-0000-000000979003','00000000-0000-0000-0000-000000979012','RUNNING',
                     'worker-1',now(),NULL),
                    -- Waiting for a claim: none.
                    ('00000000-0000-0000-0000-000000979004','00000000-0000-0000-0000-000000979013','QUEUED',
                     NULL,NULL,NULL),
                    -- Admitted, then claimed by 0.x: one drain; recovery hands it on to compose.
                    ('00000000-0000-0000-0000-000000979005','00000000-0000-0000-0000-000000979014','RUNNING',
                     'worker-0x',NULL,repeat('a',64)))
                    AS job(id,review,status,worker_id,leased_until,digest)
                """);
    }
}
