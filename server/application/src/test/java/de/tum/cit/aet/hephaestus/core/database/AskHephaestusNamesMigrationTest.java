package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.DriverManager;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The stopped upgrade that gives Ask Hephaestus and model assignments their names: rows written under the previous
 * names keep their meaning under the new ones, and the triggers and checks of the renamed tables still hold.
 */
@Tag("database")
class AskHephaestusNamesMigrationTest {

    private static final String CHANGELOG = "-refactor-ask-hephaestus-renames.xml";
    private static final long WORKSPACE = 979101;
    private static final long ACCOUNT = 979102;
    private static final long MODEL = 979105;
    private static final long EVENT = 979106;

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldKeepEveryRowAndItsMeaningUnderTheNewNames(String context) throws Exception {
        var database = PostgreSQLTestContainer.createDatabase("ask_names_" + context);
        PracticeMigrationFixture.upgradeBefore(database, context, CHANGELOG);
        String conversation = UUID.randomUUID().toString();
        String run = UUID.randomUUID().toString();
        String turn = UUID.randomUUID().toString();
        String answer = UUID.randomUUID().toString();
        String token = UUID.randomUUID().toString();
        String request = UUID.randomUUID().toString();
        PracticeMigrationFixture.execute(database, """
                INSERT INTO workspace(id,slug,display_name,account_login,account_type,status,
                    practice_review_auto_trigger_enabled,practice_review_manual_trigger_enabled)
                VALUES(%1$d,'w%1$d','W%1$d','w%1$d','ORG','ACTIVE',false,true);
                INSERT INTO account(id,display_name) VALUES(%2$d,'Developer');
                INSERT INTO mentor_conversation(id,workspace_id,account_id,created_at,last_activity_at)
                VALUES('%3$s',%1$d,%2$d,now(),now());
                BEGIN;
                INSERT INTO agent_run(id,workspace_id,kind,band,concurrency_key,state,attempt,lease_epoch)
                VALUES('%4$s',%1$d,'TURN','INTERACTIVE','conversation:%3$s','QUEUED',0,0);
                INSERT INTO mentor_turn(id,workspace_id,conversation_id,request_id,surface,status,attempt,run_id,created_at,queued_at)
                VALUES('%5$s',%1$d,'%3$s','request-1','APP','QUEUED',0,'%4$s',now(),now());
                COMMIT;
                INSERT INTO mentor_entry(id,workspace_id,conversation_id,seq,kind,surface,text,search_text,turn_id,created_at)
                VALUES(gen_random_uuid(),%1$d,'%3$s',1,'USER','APP','Why?','Why?','%5$s',now()),
                      ('%6$s',%1$d,'%3$s',2,'HEPH','APP','Because.','Because.','%5$s',now());
                INSERT INTO notification_subscription(id,account_id,kind,enabled,unsubscribe_token,version)
                VALUES(gen_random_uuid(),%2$d,'MENTOR_DIGEST',true,'%7$s',0);
                INSERT INTO agent_action(id,kind,lane_key,idempotency_key,state,max_attempts,next_attempt_at,payload,
                    created_at,updated_at)
                VALUES(gen_random_uuid(),'EMAIL','email:%2$d','email:mentor_digest:%2$d','ADMITTED',3,now(),
                    '{"kind":"MENTOR_DIGEST","event":{"accountId":%2$d}}',now(),now());
                INSERT INTO llm_call_pool(id,created_at,display_name,slug) OVERRIDING SYSTEM VALUE
                VALUES(%8$d,now(),'Pool','pool-%8$d');
                INSERT INTO llm_connection(id,slug,display_name,base_url,api_protocol,created_at) OVERRIDING SYSTEM VALUE
                VALUES(%8$d,'connection-%8$d','Connection','https://llm.example/v1','openai-completions',now());
                INSERT INTO llm_model(id,connection_id,slug,display_name,upstream_model_id,created_at,call_pool_id)
                OVERRIDING SYSTEM VALUE VALUES(%8$d,%8$d,'model-%8$d','Model','model',now(),%8$d);
                INSERT INTO workspace_agent_binding(workspace_id,purpose,instance_model_id) VALUES(%1$d,'MENTOR',%8$d);
                INSERT INTO person_data_request(id,state,created_at,expires_at,version,selections_json,counts_json,
                    completed_json)
                VALUES('%9$s','ERASING',now(),now() + interval '1 day',0,
                    '{"mentor_entry":{"ids":[1]},"review_job":{}}','{"mentor_entry":2,"mentor_turn":1}',
                    '{"mentor_conversation":{"count":1}}');
                INSERT INTO provider_event(id,provider,subject,scope_key,delivery_key,payload,state,attempt,failures,
                    lease_epoch) OVERRIDING SYSTEM VALUE
                VALUES(%10$d,'slack','slack.event','scope','delivery-%10$d','\\x7b7d','QUEUED',1,0,1);
                INSERT INTO provider_event_step(event_id,step_name) VALUES(%10$d,'slack.mentor.submit');
                """.formatted(
                        WORKSPACE, ACCOUNT, conversation, run, turn, answer, token, MODEL, request, EVENT));

        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            PracticeMigrationFixture.upgradeOnly(liquibase, context, CHANGELOG);

            assertThat(PracticeMigrationFixture.column(database, """
                    SELECT (SELECT count(*) FROM pg_class
                            WHERE relnamespace='public'::regnamespace AND relname ~ 'mentor|agent_binding')
                         + (SELECT count(*) FROM pg_constraint
                            WHERE connamespace='public'::regnamespace AND conname ~ 'mentor|agent_binding')
                         + (SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal AND tgname ~ 'mentor')
                         + (SELECT count(*) FROM pg_proc
                            WHERE pronamespace='public'::regnamespace AND proname ~ 'mentor|agent_binding')
                    """))
                    .as("no table, index, sequence, constraint, trigger or function keeps a previous name")
                    .containsExactly("0");
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT kind FROM conversation_entry WHERE workspace_id=" + WORKSPACE + " ORDER BY seq"))
                    .containsExactly("USER", "ANSWER");
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT practice_review_event_enabled || '/' || practice_review_request_enabled"
                                    + " FROM workspace WHERE id=" + WORKSPACE))
                    .containsExactly("false/true");
            assertThat(PracticeMigrationFixture.column(
                            database, "SELECT purpose FROM workspace_model_assignment WHERE workspace_id=" + WORKSPACE))
                    .containsExactly("CONVERSATION");
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT kind || '/' || unsubscribe_token FROM notification_subscription WHERE account_id="
                                    + ACCOUNT))
                    .as("the unsubscribe link of a sent digest still works")
                    .containsExactly("WEEKLY_DIGEST/" + token);
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT payload->>'kind' || '/' || state FROM agent_action WHERE lane_key='email:" + ACCOUNT
                                    + "'"))
                    .as("a queued digest email is still sent, under its new kind")
                    .containsExactly("WEEKLY_DIGEST/ADMITTED");
            assertThat(PracticeMigrationFixture.column(database, """
                    SELECT jsonb_exists(selections_json::jsonb, 'conversation_entry')
                        AND counts_json::jsonb = '{"conversation_entry":2,"conversation_turn":1}'::jsonb
                        AND jsonb_exists(completed_json::jsonb, 'conversation')
                        AND NOT jsonb_exists(selections_json::jsonb, 'mentor_entry')
                    FROM person_data_request WHERE id='%s'
                    """.formatted(request)))
                    .as("an erasure in progress names the stores as the server now registers them")
                    .containsExactly("t");
            assertThat(PracticeMigrationFixture.column(
                            database, "SELECT step_name FROM provider_event_step WHERE event_id=" + EVENT))
                    .as("a pending Slack event does not repeat a write it already committed")
                    .containsExactly("slack.conversation.submit");
            assertThat(PracticeMigrationFixture.column(database, """
                    SELECT pg_get_constraintdef(oid) ~ 'MODEL_ASSIGNMENT' AND pg_get_constraintdef(oid) ~ 'AGENT_BINDING'
                    FROM pg_constraint WHERE conname='ck_config_audit_event_entity_type'
                    """))
                    .as("new audit rows name the model assignment, and the earlier rows keep their spelling")
                    .containsExactly("t");
            assertThat(PracticeMigrationFixture.column(database, """
                    SELECT pg_get_constraintdef(oid) ~ 'CONVERSATION_TURN' AND pg_get_constraintdef(oid) !~ 'MENTOR_TURN'
                    FROM pg_constraint WHERE conname='ck_llm_usage_event_source_type'
                    """)).containsExactly("t");
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(database, """
                    INSERT INTO conversation_entry(id,workspace_id,conversation_id,seq,kind,surface,text,search_text,created_at)
                    VALUES(gen_random_uuid(),%1$d,'%2$s',3,'HEPH','APP','Old speaker','Old speaker',now())
                    """.formatted(WORKSPACE, conversation)))
                    .hasMessageContaining("chk_conversation_entry_kind");
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                            database,
                            "UPDATE agent_action SET payload = payload || '{\"x\":1}' WHERE lane_key='email:" + ACCOUNT
                                    + "'"))
                    .as("the action lifecycle guard is on again")
                    .hasMessageContaining("an action payload changes only");
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                            database, "UPDATE conversation_turn SET surface='SYSTEM' WHERE id='" + turn + "'"))
                    .as("the renamed turn trigger still checks its run")
                    .hasMessageContaining("a conversation turn needs its conversation run kind");
            assertThat(PracticeMigrationFixture.column(database, """
                    SELECT count(*)::text FROM pg_proc
                    WHERE pronamespace='public'::regnamespace
                      AND proname <> 'workspace_slug_assignable'
                      AND prosrc ~ '(mentor_(turn|entry|conversation)|agent_binding|''HEPH'')'
                    """))
                    .as("no function body reads a previous table or speaker")
                    .containsExactly("0");

            assertThatThrownBy(() -> PracticeMigrationFixture.rollbackOnly(liquibase, context, CHANGELOG))
                    .hasMessageContaining("restore the verified pre-upgrade database backup");
        }
    }
}
