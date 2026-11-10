package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class AgentRunHoldMigrationTest {
    private static final String CHANGELOG = "20261110075504-feat-run-hold.xml";
    private static final long WORKSPACE = 997101;
    private static final long ITEM = 997102;

    @Test
    void shouldMoveAWaitingJobsHoldToItsRunAndEndTheHoldOfAJobWhoseRunLeftTheQueue() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "agent_run_hold_migration_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        PracticeMigrationFixture.execute(database, """
                INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
                VALUES(%1$d,'w%1$d','W%1$d','w%1$d','ORG','ACTIVE');
                INSERT INTO item(id,workspace_id,kind,source_id) VALUES(%2$d,%1$d,'scm.pull_request',42);
                """.formatted(WORKSPACE, ITEM));
        String budget = job(database, "QUEUED", "BUDGET");
        String room = job(database, "QUEUED", "SANDBOX_ROOM");
        String waiting = job(database, "QUEUED", null);
        String running = job(database, "RUNNING", "BUDGET");

        migrate(database);

        assertThat(hold(database, budget)).isEqualTo("BUDGET");
        assertThat(hold(database, room)).isEqualTo("SANDBOX_ROOM");
        assertThat(hold(database, waiting)).as("a backoff is no hold").isEqualTo("-");
        assertThat(hold(database, running)).as("a leased run waits for nothing").isEqualTo("-");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT count(*)::text FROM information_schema.columns"
                                + " WHERE table_name='review_job' AND column_name='hold_reason'"))
                .containsExactly("0");
    }

    private static String job(TestDatabase database, String status, @Nullable String hold) {
        String review = UUID.randomUUID().toString();
        String run = UUID.randomUUID().toString();
        String job = UUID.randomUUID().toString();
        boolean leased = status.equals("RUNNING");
        PracticeMigrationFixture.execute(database, """
                BEGIN;
                INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,status,ordered_at)
                VALUES('%1$s',%2$d,%3$d,'ITEM','v1','REQUEST','RUNNING',now());
                INSERT INTO agent_run(id,workspace_id,kind,band,concurrency_key,state,attempt,lease_epoch,leased_until,
                    available_at,created_at,started_at)
                VALUES('%4$s',%2$d,'REVIEW','BATCH','review:%1$s','%6$s',0,%7$d,%8$s,now(),now(),%9$s);
                INSERT INTO review_job(id,review_id,workspace_id,run_id,submitted_at,status,started_at,trace_id,input,
                    config_snapshot,capture,hold_reason)
                VALUES('%5$s','%1$s',%2$d,'%4$s',now(),'%10$s',%9$s,'trace','{}','{}','{}',%11$s);
                COMMIT;
                """.formatted(
                        review,
                        WORKSPACE,
                        ITEM,
                        run,
                        job,
                        leased ? "LEASED" : "QUEUED",
                        leased ? 1 : 0,
                        leased ? "now() + interval '10 minutes'" : "NULL",
                        leased ? "now()" : "NULL",
                        status,
                        hold == null ? "NULL" : "'" + hold + "'"));
        return job;
    }

    private static String hold(TestDatabase database, String job) {
        return PracticeMigrationFixture.column(database, """
                        SELECT COALESCE(r.hold_reason, '-')
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
