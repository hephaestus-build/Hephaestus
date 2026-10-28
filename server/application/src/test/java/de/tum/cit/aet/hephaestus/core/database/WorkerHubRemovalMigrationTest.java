package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
    void shouldCountALeaselessClaimedAttemptAsADrainAndDropTheTokenDenylist() throws Exception {
        var database = PracticeMigrationFixture.releasedDatabase("dev");
        PracticeMigrationFixture.upgradeBefore(database, "dev", CHANGELOG);
        new ResourceDatabasePopulator(new ClassPathResource("db/practice-native/result-parent.sql"))
                .execute(new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password()));
        PracticeMigrationFixture.execute(database, """
                INSERT INTO review_job
                    (id,review_id,workspace_id,submitted_at,status,processing_status,trace_id,input,config_snapshot,
                     attempt,worker_id,leased_until)
                SELECT job.id::uuid,review.id,review.workspace_id,now(),job.status,'NONE',
                       '00000000000000000000000000000001','{}','{}',1,job.worker_id,job.leased_until
                FROM review,
                    (VALUES ('00000000-0000-0000-0000-000000979001','RUNNING','worker-0x',NULL::timestamptz),
                            ('00000000-0000-0000-0000-000000979002','FAILED','worker-0x',NULL))
                        AS job(id,status,worker_id,leased_until)
                WHERE review.id='00000000-0000-0000-0000-000000978011'
                """);

        PracticeMigrationFixture.upgrade(database, "dev");

        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT id || ':' || drain_count FROM review_job WHERE id::text LIKE '%979%' ORDER BY id"))
                .containsExactly("00000000-0000-0000-0000-000000979001:1", "00000000-0000-0000-0000-000000979002:0");
        assertThat(
                        PracticeMigrationFixture.column(
                                database,
                                "SELECT count(*)::text FROM information_schema.tables WHERE table_name='worker_token_denylist'"))
                .containsExactly("0");
    }
}
