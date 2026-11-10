package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
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

@Tag("database")
class AgentRunMigrationTest {
    private static final String CHANGELOG = "20261109000559-feat-unified-agent-runs.xml";
    private static final long WORKSPACE = 997001;
    private static final long ITEM = 997002;

    @Test
    void shouldGiveEveryReviewJobARunThatAgreesWithItsStatusAndKeepItsSpentRetries() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "agent_run_migration_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        PracticeMigrationFixture.execute(database, """
                INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
                VALUES(%1$d,'w%1$d','W%1$d','w%1$d','ORG','ACTIVE');
                INSERT INTO item(id,workspace_id,kind,source_id) VALUES(%2$d,%1$d,'scm.pull_request',42);
                """.formatted(WORKSPACE, ITEM));
        // attempt, drain, room refusals, progressed: what each job's run must carry as its spent retries.
        String queued = job(database, "QUEUED", "REQUEST", 3, 1, 1, 0);
        String running = job(database, "RUNNING", "SCHEDULE", 2, 0, 0, 1);
        String completed = job(database, "COMPLETED", "EVENT", 0, 0, 0, 0);
        String cancelled = job(database, "CANCELLED", "BACKFILL", 1, 0, 0, 0);
        String overDrained = job(database, "FAILED", "REQUEST", 1, 2, 0, 0);

        migrate(database);

        assertThat(run(database, queued)).isEqualTo("QUEUED/BATCH/1/3/f/-");
        assertThat(run(database, running))
                .as("a job a worker held keeps running on an expired lease, for the run reaper to take back")
                .isEqualTo("LEASED/BACKGROUND/1/3/t/-");
        assertThat(run(database, completed)).isEqualTo("ENDED/BATCH/0/1/f/COMPLETED");
        assertThat(run(database, cancelled)).isEqualTo("ENDED/BACKGROUND/1/2/f/CANCELLED");
        assertThat(run(database, overDrained)).isEqualTo("ENDED/BATCH/0/2/f/COMPLETED");
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT status FROM review_job WHERE id='" + running + "'"))
                .containsExactly("RUNNING");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT count(*)::text FROM information_schema.columns WHERE table_name='review_job'"
                                + " AND column_name IN ('lease_id','leased_until','attempt','drain_count',"
                                + "'room_refusals','progressed_attempts','available_at')"))
                .containsExactly("0");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                        database,
                        "UPDATE review_job SET status='QUEUED',completed_at=NULL,cancellation_reason=NULL WHERE id='"
                                + running + "'"))
                .as("a job and its run that disagree are refused at commit")
                .hasMessageContaining("needs a run that agrees");
    }

    private static String job(
            TestDatabase database,
            String status,
            String origin,
            int attempt,
            int drains,
            int roomRefusals,
            int progressed) {
        String review = UUID.randomUUID().toString();
        String job = UUID.randomUUID().toString();
        PracticeMigrationFixture.execute(database, """
                INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,status,ordered_at)
                VALUES('%1$s',%2$d,%3$d,'ITEM','v1','%4$s','RUNNING',now());
                INSERT INTO review_job(id,review_id,workspace_id,submitted_at,status,attempt,drain_count,room_refusals,
                    progressed_attempts,started_at,completed_at,trace_id,input,config_snapshot,capture)
                VALUES('%5$s','%1$s',%2$d,now(),'%6$s',%7$d,%8$d,%9$d,%10$d,
                    CASE WHEN '%6$s'='QUEUED' THEN NULL ELSE now() END,
                    CASE WHEN '%6$s' IN ('QUEUED','RUNNING') THEN NULL ELSE now() END,
                    'trace','{}','{}','{}');
                """.formatted(
                        review, WORKSPACE, ITEM, origin, job, status, attempt, drains, roomRefusals, progressed));
        return job;
    }

    /** state/band/spent/epoch/whether the lease has expired/stop reason of the job's run. */
    private static String run(TestDatabase database, String job) {
        return PracticeMigrationFixture.column(database, """
                        SELECT r.state || '/' || r.band || '/' || r.attempt || '/' || r.lease_epoch || '/'
                            || CASE WHEN r.leased_until <= clock_timestamp() THEN 't' ELSE 'f' END || '/'
                            || COALESCE(r.stop_reason, '-')
                        FROM review_job j JOIN agent_run r ON r.id=j.run_id AND r.workspace_id=j.workspace_id
                        WHERE j.id='%s'
                        """.formatted(job)).getFirst();
    }

    private static void migrate(TestDatabase database) throws Exception {
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/changelog/next/" + CHANGELOG,
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            liquibase.update(new Contexts("prod"), new LabelExpression());
        }
    }
}
