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
class AgentTurnRunMigrationTest {
    private static final String CHANGELOG = "20261111235331-feat-turns-on-agent-runs.xml";
    private static final long WORKSPACE = 997201;
    private static final long ACCOUNT = 997202;

    @Test
    void shouldGiveEveryTurnARunThatAgreesWithItsStatusAndKeepTheClaimOfAHeldTurn() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "agent_turn_run_migration_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        PracticeMigrationFixture.execute(database, """
                INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
                VALUES(%1$d,'w%1$d','W%1$d','w%1$d','ORG','ACTIVE');
                """.formatted(WORKSPACE));
        // its developer, status, attempt, whether a host holds its lease, whether it started
        String queued = turn(database, ACCOUNT + 1, "QUEUED", 0, false, false);
        String running = turn(database, ACCOUNT + 2, "RUNNING", 1, true, true);
        String stopping = turn(database, ACCOUNT + 3, "STOPPED", 1, true, true);
        String stoppedQueued = turn(database, ACCOUNT + 4, "STOPPED", 0, false, false);
        String done = turn(database, ACCOUNT + 5, "DONE", 2, false, true);

        migrate(database);

        assertThat(run(database, queued)).isEqualTo("QUEUED/0/0/180/-");
        assertThat(run(database, running))
                .as("a held turn keeps its claim, for the run reaper to take back once its lease runs out")
                .isEqualTo("LEASED/1/0/180/-");
        assertThat(run(database, stopping)).isEqualTo("LEASED/1/0/180/-");
        assertThat(run(database, stoppedQueued)).isEqualTo("ENDED/0/0/180/CANCELLED");
        assertThat(run(database, done)).isEqualTo("ENDED/2/1/180/COMPLETED");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT count(*)::text FROM information_schema.columns WHERE table_name='mentor_turn'"
                                + " AND column_name IN ('lease_id','leased_until')"))
                .containsExactly("0");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                        database, "UPDATE mentor_turn SET status='DONE' WHERE id='" + running + "'"))
                .as("a turn and its run that disagree are refused at commit")
                .hasMessageContaining("needs a run that agrees");
    }

    /** Inserts a turn of its own developer's conversation, as the previous release left it. */
    private static String turn(
            TestDatabase database, long account, String status, int attempt, boolean held, boolean started) {
        String conversation = UUID.randomUUID().toString();
        String turn = UUID.randomUUID().toString();
        PracticeMigrationFixture.execute(database, """
                INSERT INTO account(id,display_name) VALUES(%3$d,'Developer');
                INSERT INTO mentor_conversation(id,workspace_id,account_id,created_at,last_activity_at)
                VALUES('%1$s',%2$d,%3$d,now(),now());
                INSERT INTO mentor_turn(id,workspace_id,conversation_id,request_id,status,surface,attempt,lease_id,
                    leased_until,created_at,queued_at,started_at,finished_at)
                VALUES('%4$s',%2$d,'%1$s','request-%4$s','%5$s','APP',%6$d,%7$s,%8$s,now(),now(),%9$s,%10$s);
                """.formatted(
                        conversation,
                        WORKSPACE,
                        account,
                        turn,
                        status,
                        attempt,
                        held ? "gen_random_uuid()" : "NULL",
                        held ? "now() + interval '1 minute'" : "NULL",
                        started ? "now()" : "NULL",
                        status.equals("QUEUED") || held ? "NULL" : "now()"));
        return turn;
    }

    /** state/epoch/spent attempts/start window/stop reason of the turn's run. */
    private static String run(TestDatabase database, String turn) {
        return PracticeMigrationFixture.column(database, """
                        SELECT r.state || '/' || r.lease_epoch || '/' || r.attempt || '/' || r.start_within_seconds
                            || '/' || COALESCE(r.stop_reason, '-')
                        FROM mentor_turn t JOIN agent_run r ON r.id=t.run_id AND r.workspace_id=t.workspace_id
                        WHERE t.id='%s' AND r.kind='TURN'
                          AND r.concurrency_key='conversation:' || t.conversation_id
                        """.formatted(turn)).getFirst();
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
