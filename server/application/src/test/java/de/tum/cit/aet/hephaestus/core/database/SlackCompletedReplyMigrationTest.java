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
import org.junit.jupiter.api.Test;

@Tag("database")
class SlackCompletedReplyMigrationTest {
    private static final String CHANGELOG = "-act-slack-mentor-outbox.xml";

    @Test
    void shouldMoveLegacyOutcomesAndRefuseToRepeatPossiblyStreamedReplies() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "slack_cutover_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        UUID conversation = UUID.randomUUID();
        UUID delivered = UUID.randomUUID();
        UUID failed = UUID.randomUUID();
        UUID streaming = UUID.randomUUID();
        UUID waiting = UUID.randomUUID();
        UUID redactedReply = UUID.randomUUID();
        UUID redactedDigest = UUID.randomUUID();
        PracticeMigrationFixture.execute(database, """
            INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
            VALUES(997801,'slack-cutover','Slack','slack-cutover','ORG','ACTIVE');
            INSERT INTO account(id,display_name) VALUES(997802,'Developer');
            INSERT INTO mentor_conversation(id,workspace_id,account_id,created_at,last_activity_at)
            VALUES('%1$s',997801,997802,now(),now());
            INSERT INTO mentor_entry(id,workspace_id,conversation_id,seq,kind,surface,text,search_text,slack_delivery,slack_stream_ts,created_at)
            VALUES('%2$s',997801,'%1$s',1,'USER','SLACK','question','question','DELIVERED',NULL,now()),
                  ('%3$s',997801,'%1$s',2,'USER','SLACK','question','question','FAILED',NULL,now()),
                  ('%4$s',997801,'%1$s',3,'USER','SLACK','question','question',NULL,'200.1',now()),
                  ('%5$s',997801,'%1$s',4,'USER','SLACK','question','question',NULL,NULL,now());
            INSERT INTO mentor_entry(id,workspace_id,conversation_id,seq,kind,surface,text,slack_delivery,redacted_at,created_at)
            VALUES('%6$s',997801,'%1$s',5,'USER','SLACK','','DELIVERED',now(),now()),
                  ('%7$s',997801,'%1$s',6,'DIGEST','SYSTEM','','UNCERTAIN',now(),now());
            INSERT INTO agent_action(id,workspace_id,kind,lane_key,idempotency_key,state,payload,subject_account_id,
                attempts,max_attempts,next_attempt_at,created_at,updated_at)
            VALUES(uuidv7(),997801,'SLACK_POST','slack_reply:%4$s','slack_reply:%4$s','ADMITTED',
                jsonb_build_object('entryId','%4$s','turnId',NULL,'taken',true),997802,0,3,now(),now(),now()),
                (uuidv7(),997801,'SLACK_POST','slack_reply:%5$s','slack_reply:%5$s','ADMITTED',
                jsonb_build_object('entryId','%5$s','turnId',NULL,'taken',false),997802,0,3,now(),now(),now());
            """.formatted(
                        conversation, delivered, failed, streaming, waiting, redactedReply, redactedDigest));
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG);
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT state FROM agent_action WHERE idempotency_key='slack_reply:" + delivered + "'"))
                    .containsExactly("DONE");
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT state FROM agent_action WHERE idempotency_key='slack_reply:" + failed + "'"))
                    .containsExactly("FAILED");
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT state || '/' || stop_reason || '/' || (payload IS NULL)::text FROM agent_action WHERE idempotency_key='slack_reply:"
                                    + streaming + "'"))
                    .containsExactly("UNCERTAIN/WRITE_UNCONFIRMED/true");
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT state || '/' || jsonb_exists(payload, 'taken')::text FROM agent_action WHERE idempotency_key='slack_reply:"
                                    + waiting + "'"))
                    .containsExactly("ADMITTED/false");
            assertThat(
                            PracticeMigrationFixture.column(
                                    database,
                                    "SELECT count(*)::text FROM information_schema.columns WHERE table_name='mentor_entry' AND column_name IN ('slack_delivery','slack_stream_ts')"))
                    .containsExactly("0");
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT count(*)::text FROM agent_action WHERE workspace_id=997801 AND idempotency_key IN ('slack_reply:"
                                    + redactedReply + "','slack_digest:" + redactedDigest + "')"))
                    .containsExactly("0");
            assertThat(PracticeMigrationFixture.column(
                            database, "SELECT tgenabled::text FROM pg_trigger WHERE tgname='agent_action_lifecycle'"))
                    .containsExactly("O");
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                            database,
                            "UPDATE agent_action SET state='ADMITTED',payload='{}' WHERE idempotency_key='slack_reply:"
                                    + delivered + "'"))
                    .hasMessageContaining("immutable");
            assertThatThrownBy(() -> PracticeMigrationFixture.rollbackOnly(liquibase, "prod", CHANGELOG))
                    .hasStackTraceContaining("pre-upgrade database backup");
        }
    }
}
