package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Retiring the tools pool must leave conversation history and the live load budget intact. */
@Tag("database")
class ConversationSandboxMigrationTest {
    private static final String CHANGELOG = "-sbx-turn-cutover.xml";
    private static final long WORKSPACE = 979301;
    private static final long ACCOUNT = 979302;
    private static final String CONVERSATION = "00000000-0000-0000-0000-000000979303";
    private static final String RUNNING_RUN = "00000000-0000-0000-0000-000000979311";
    private static final String ENDED_RUN = "00000000-0000-0000-0000-000000979312";
    private static final String RUNNING_TURN = "00000000-0000-0000-0000-000000979321";
    private static final String ENDED_TURN = "00000000-0000-0000-0000-000000979322";
    private static final String ENTRY = "00000000-0000-0000-0000-000000979331";

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldDropTheRetiredToolsPoolWithoutChangingTurnsRunsHistoryOrLoadCounts(String context) throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "conversation_sandbox_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, context, CHANGELOG);
        seed(database);
        assertThat(PracticeMigrationFixture.column(database, """
                        SELECT name FROM conversation_tools_pod
                        WHERE name IN ('upgrade-warm', 'upgrade-assigned', 'upgrade-revoked') ORDER BY name
                        """))
                .containsExactly("upgrade-assigned", "upgrade-revoked", "upgrade-warm");
        String retained = """
                SELECT 'turn/' || to_jsonb(t)::text AS history FROM mentor_turn t WHERE workspace_id=%1$d
                UNION ALL
                SELECT 'run/' || to_jsonb(r)::text FROM agent_run r WHERE workspace_id=%1$d
                UNION ALL
                SELECT 'entry/' || to_jsonb(e)::text FROM mentor_entry e WHERE workspace_id=%1$d
                UNION ALL
                SELECT 'conversation/' || to_jsonb(c)::text FROM mentor_conversation c WHERE workspace_id=%1$d
                ORDER BY history
                """.formatted(WORKSPACE);
        var before = PracticeMigrationFixture.column(database, retained);
        assertThat(before).hasSize(6);

        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            PracticeMigrationFixture.upgradeOnly(liquibase, context, CHANGELOG);

            assertThat(PracticeMigrationFixture.column(
                            database, "SELECT (to_regclass('public.conversation_tools_pod') IS NULL)::text"))
                    .containsExactly("true");
            assertThat(PracticeMigrationFixture.column(database, retained)).isEqualTo(before);
            assertThat(PracticeMigrationFixture.column(database, """
                            SELECT status || '/' || tools_loads FROM mentor_turn
                            WHERE workspace_id=%d ORDER BY status
                            """.formatted(WORKSPACE)))
                    .containsExactly("DONE/2", "RUNNING/5");
            assertThatThrownBy(() -> PracticeMigrationFixture.rollbackOnly(liquibase, context, CHANGELOG))
                    .hasStackTraceContaining("Restore the verified pre-upgrade backup");
        }

        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT (to_regclass('public.conversation_tools_pod') IS NULL)::text"))
                .containsExactly("true");
        assertThat(PracticeMigrationFixture.column(database, retained)).isEqualTo(before);
        assertThat(PracticeMigrationFixture.column(database, """
                        SELECT status || '/' || tools_loads FROM mentor_turn
                        WHERE workspace_id=%d ORDER BY status
                        """.formatted(WORKSPACE)))
                .containsExactly("DONE/2", "RUNNING/5");
    }

    private static void seed(TestDatabase database) {
        PracticeMigrationFixture.execute(database, """
                INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
                VALUES(%1$d,'w%1$d','W%1$d','w%1$d','ORG','ACTIVE');
                INSERT INTO account(id,display_name) VALUES(%2$d,'Developer');
                INSERT INTO mentor_conversation(id,workspace_id,account_id,created_at,last_activity_at)
                VALUES('%3$s',%1$d,%2$d,now(),now());
                INSERT INTO agent_run(id,workspace_id,kind,band,concurrency_key,state,attempt,lease_epoch,
                    leased_until,started_at,ended_at,stop_reason)
                VALUES('%4$s',%1$d,'TURN','INTERACTIVE','conversation:%3$s','LEASED',0,1,
                    now() + interval '10 minutes',now(),NULL,NULL),
                    ('%5$s',%1$d,'TURN','INTERACTIVE','conversation:%3$s','ENDED',0,1,NULL,now(),now(),'COMPLETED');
                INSERT INTO mentor_turn(id,workspace_id,conversation_id,request_id,status,surface,attempt,run_id,
                    tools_loads,created_at,queued_at,started_at,finished_at)
                VALUES('%6$s',%1$d,'%3$s','request-%6$s','RUNNING','APP',1,'%4$s',5,now(),now(),now(),NULL),
                    ('%7$s',%1$d,'%3$s','request-%7$s','DONE','APP',1,'%5$s',2,now(),now(),now(),now());
                INSERT INTO mentor_entry(id,workspace_id,conversation_id,seq,turn_id,kind,surface,text,payload,created_at)
                VALUES('%8$s',%1$d,'%3$s',1,'%7$s','HEPH','APP','Existing answer',
                    '{"type":"message","message":{"role":"assistant","content":[{"type":"text","text":"Existing answer"}]}}',now());
                INSERT INTO conversation_tools_pod(name,nonce,workspace_id,turn_id,attempt,address,revoked)
                VALUES('upgrade-warm',gen_random_uuid(),NULL,NULL,NULL,NULL,false),
                    ('upgrade-assigned',gen_random_uuid(),%1$d,'%6$s',1,'10.0.0.2',false),
                    ('upgrade-revoked',gen_random_uuid(),%1$d,'%7$s',1,'10.0.0.3',true);
                """.formatted(
                        WORKSPACE, ACCOUNT, CONVERSATION, RUNNING_RUN, ENDED_RUN, RUNNING_TURN, ENDED_TURN, ENTRY));
    }
}
