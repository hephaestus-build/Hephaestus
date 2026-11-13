package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
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

@Tag("database")
class ReviewRunOutcomeMigrationTest {
    private static final String CHANGELOG = "-run-review-run-outcomes.xml";
    private static final long WORKSPACE = 970801;

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldCorrectOldSubjectOutcomesAndRefuseFalseCompletions(String context) throws Exception {
        var database = PostgreSQLTestContainer.createDatabase("review_run_outcome_" + context);
        PracticeMigrationFixture.upgradeBefore(database, context, CHANGELOG);
        PracticeMigrationFixture.execute(database, """
                INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
                VALUES(%1$d,'w%1$d','W%1$d','w%1$d','ORG','ACTIVE')
                """.formatted(WORKSPACE));
        List<String> statuses = List.of("COMPLETED", "FAILED", "TIMED_OUT", "CANCELLED");
        int sequence = 0;
        String completed = "";
        for (String kind : List.of("REVIEW", "REVIEW_COMPOSE")) {
            for (String status : statuses) {
                String run = seed(database, status, kind, ++sequence);
                if (completed.isEmpty()) completed = run;
            }
        }
        for (String status : List.of("DONE", "FAILED", "STOPPED")) seedTurn(database, status);
        String identity =
                "SELECT id::text || ':' || lease_epoch || ':' || attempt || ':' || started_at::text || ':' || ended_at::text"
                        + " FROM agent_run WHERE workspace_id=" + WORKSPACE + " ORDER BY id";
        var before = PracticeMigrationFixture.column(database, identity);
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            PracticeMigrationFixture.upgradeOnly(liquibase, context, CHANGELOG);
            assertThat(PracticeMigrationFixture.column(database, identity)).isEqualTo(before);
            assertThat(PracticeMigrationFixture.column(database, """
                    SELECT j.status || ':' || r.stop_reason FROM review_job j
                    JOIN agent_run r ON r.id=j.run_id AND r.workspace_id=j.workspace_id
                    WHERE j.workspace_id=%d ORDER BY j.status, r.kind
                    """.formatted(WORKSPACE)))
                    .containsExactly(
                            "CANCELLED:CANCELLED",
                            "CANCELLED:CANCELLED",
                            "COMPLETED:COMPLETED",
                            "COMPLETED:COMPLETED",
                            "FAILED:FAILED",
                            "FAILED:FAILED",
                            "TIMED_OUT:DEADLINE",
                            "TIMED_OUT:DEADLINE");
            assertThat(PracticeMigrationFixture.column(database, """
                    SELECT t.status || ':' || r.stop_reason FROM mentor_turn t
                    JOIN agent_run r ON r.id=t.run_id AND r.workspace_id=t.workspace_id
                    WHERE t.workspace_id=%d ORDER BY t.status
                    """.formatted(WORKSPACE)))
                    .containsExactly("DONE:COMPLETED", "FAILED:FAILED", "STOPPED:CANCELLED");
            assertThat(PracticeMigrationFixture.column(
                            database, "SELECT tgenabled::text FROM pg_trigger WHERE tgname='agent_run_transition'"))
                    .containsExactly("O");
            String completedRun = completed;
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                            database, "UPDATE agent_run SET stop_reason='FAILED' WHERE id='" + completedRun + "'"))
                    .hasMessageContaining("an ended run is final");
            String active = seed(database, "RUNNING", "REVIEW", ++sequence);
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(database, """
                    BEGIN;
                    UPDATE review_job SET status='FAILED',completed_at=now() WHERE run_id='%1$s';
                    UPDATE agent_run SET state='ENDED',leased_until=NULL,stop_reason='COMPLETED',ended_at=now() WHERE id='%1$s';
                    COMMIT;
                    """.formatted(active)))
                    .hasMessageContaining("a review that did not complete cannot have a completed run");
            String activeTurn = seedTurn(database, "RUNNING");
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(database, """
                    BEGIN;
                    UPDATE mentor_turn SET status='FAILED',finished_at=now() WHERE run_id='%1$s';
                    UPDATE agent_run SET state='ENDED',leased_until=NULL,stop_reason='COMPLETED',ended_at=now() WHERE id='%1$s';
                    COMMIT;
                    """.formatted(activeTurn)))
                    .hasMessageContaining("a turn that did not complete cannot have a completed run");
            assertThatThrownBy(() -> PracticeMigrationFixture.rollbackOnly(liquibase, context, CHANGELOG))
                    .hasMessageContaining("Restore the verified pre-upgrade database backup");
            assertThat(liquibase.listUnrunChangeSets(new Contexts(context), new LabelExpression()))
                    .noneMatch(change -> change.getFilePath().endsWith(CHANGELOG));
            assertThat(PracticeMigrationFixture.column(
                            database, "SELECT state FROM agent_run WHERE id='" + active + "'"))
                    .containsExactly("LEASED");
        }
    }

    @Test
    void shouldStopTheUpgradeWhenAnExistingSubjectStillDisagreesWithItsRun() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase("review_run_outcome_corrupt");
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        PracticeMigrationFixture.execute(database, """
                INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
                VALUES(%1$d,'w%1$d','W%1$d','w%1$d','ORG','ACTIVE')
                """.formatted(WORKSPACE));
        String run = seed(database, "COMPLETED", "REVIEW", 1);
        // Model a broken pre-upgrade row without changing any production guard.
        PracticeMigrationFixture.execute(database, """
                BEGIN;
                ALTER TABLE review_job DISABLE TRIGGER USER;
                UPDATE review_job SET status='RUNNING',completed_at=NULL WHERE run_id='%s';
                ALTER TABLE review_job ENABLE TRIGGER USER;
                COMMIT;
                """.formatted(run));
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            assertThatThrownBy(() -> PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG))
                    .hasStackTraceContaining("A subject still disagrees with its completed run");
            assertThat(PracticeMigrationFixture.column(
                            database, "SELECT stop_reason FROM agent_run WHERE id='" + run + "'"))
                    .containsExactly("COMPLETED");
            assertThat(PracticeMigrationFixture.column(
                            database, "SELECT tgenabled::text FROM pg_trigger WHERE tgname='agent_run_transition'"))
                    .containsExactly("O");
        }
    }

    private static String seedTurn(TestDatabase database, String status) {
        String conversation = UUID.randomUUID().toString();
        String turn = UUID.randomUUID().toString();
        String run = UUID.randomUUID().toString();
        boolean running = status.equals("RUNNING");
        PracticeMigrationFixture.execute(database, """
                BEGIN;
                INSERT INTO account(id,display_name) VALUES(%2$d,'Developer') ON CONFLICT(id) DO NOTHING;
                INSERT INTO mentor_conversation(id,workspace_id,account_id,created_at,last_activity_at)
                VALUES('%3$s',%1$d,%2$d,now(),now());
                INSERT INTO agent_run(id,workspace_id,kind,band,concurrency_key,state,attempt,lease_epoch,
                    leased_until,started_at,ended_at,stop_reason)
                VALUES('%4$s',%1$d,'TURN','INTERACTIVE','conversation:%3$s','%6$s',0,1,%7$s,now(),%8$s,%9$s);
                INSERT INTO mentor_turn(id,workspace_id,conversation_id,request_id,status,surface,attempt,run_id,
                    created_at,queued_at,started_at,finished_at)
                VALUES('%5$s',%1$d,'%3$s','request-%5$s','%10$s','APP',1,'%4$s',now(),now(),now(),%8$s);
                INSERT INTO mentor_entry(id,workspace_id,conversation_id,seq,turn_id,kind,surface,text,created_at)
                VALUES(gen_random_uuid(),%1$d,'%3$s',1,'%5$s','USER','APP','Question',now());
                COMMIT;
                """.formatted(
                WORKSPACE,
                WORKSPACE
                        + 100
                        + List.of("DONE", "FAILED", "STOPPED", "RUNNING").indexOf(status),
                conversation,
                run,
                turn,
                running ? "LEASED" : "ENDED",
                running ? "now()+interval '10 minutes'" : "NULL",
                running ? "NULL" : "now()",
                running ? "NULL" : "'COMPLETED'",
                status));
        return run;
    }

    private static String seed(TestDatabase database, String status, String kind, int sequence) {
        String review = UUID.randomUUID().toString();
        String run = UUID.randomUUID().toString();
        long item = WORKSPACE + sequence;
        boolean running = status.equals("RUNNING");
        PracticeMigrationFixture.execute(database, """
                BEGIN;
                INSERT INTO item(id,workspace_id,kind,source_id) VALUES(%1$d,%2$d,'test.item',%1$d);
                INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,ordered_at)
                VALUES('%3$s',%2$d,%1$d,'ITEM','test:1','REQUEST',now());
                INSERT INTO agent_run(id,workspace_id,kind,band,concurrency_key,state,attempt,lease_epoch,leased_until,
                    available_at,stop_reason,created_at,started_at,ended_at)
                VALUES('%4$s',%2$d,'%5$s','BATCH','review:%3$s','%6$s',0,1,%7$s,now(),%8$s,now(),now(),%9$s);
                INSERT INTO review_job(id,review_id,workspace_id,run_id,submitted_at,status,started_at,completed_at,trace_id,input,config_snapshot)
                VALUES(gen_random_uuid(),'%3$s',%2$d,'%4$s',now(),'%10$s',now(),%9$s,'trace','{}','{}');
                COMMIT;
                """.formatted(
                        item,
                        WORKSPACE,
                        review,
                        run,
                        kind,
                        running ? "LEASED" : "ENDED",
                        running ? "now()+interval '10 minutes'" : "NULL",
                        running ? "NULL" : "'COMPLETED'",
                        running ? "NULL" : "now()",
                        status));
        return run;
    }
}
