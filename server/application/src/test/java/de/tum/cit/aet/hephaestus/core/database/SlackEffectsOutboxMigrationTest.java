package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The Slack outbox change set on a database that holds the schema before it and real Slack rows: channels in every
 * consent state, their pause and resume history, and outbox rows. The generated change set runs unmodified. Each
 * assertion reads the migrated rows and the catalog, never the changelog text.
 */
@Tag("database")
class SlackEffectsOutboxMigrationTest {
    private static final String CHANGELOG = "-clean-slack-effects-outbox.xml";

    @Test
    void shouldDeriveTheIngestFloorFromTheDisclosureAndTheLatestResumeAndFailClosedWithoutOne() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "slack_outbox_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        PracticeMigrationFixture.execute(database, """
            INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
            VALUES(997811,'slack-outbox-a','A','slack-outbox-a','ORG','ACTIVE'),
                  (997812,'slack-outbox-b','B','slack-outbox-b','ORG','ACTIVE');
            INSERT INTO account(id,display_name) VALUES(997813,'Developer');

            -- Workspace A: every consent state of the 1.0 schema. The disclosure time is the announcement time.
            INSERT INTO slack_monitored_channel(workspace_id,slack_team_id,slack_channel_id,consent_state,consent_announced_at) VALUES
              (997811,'T1','C_SHARED','ACTIVE','2026-01-10T00:00:00Z'),
              (997811,'T1','C_PAUSED_NEVER_RESUMED','PAUSED','2026-01-10T00:00:00Z'),
              (997811,'T1','C_RESUME_BEFORE_DISCLOSURE','ACTIVE','2026-01-20T00:00:00Z'),
              (997811,'T1','C_ACTIVE_NO_DISCLOSURE','ACTIVE',NULL),
              (997811,'T1','C_PAUSED_NO_DISCLOSURE','PAUSED',NULL),
              (997811,'T1','C_PENDING','PENDING',NULL),
              (997811,'T1','C_PENDING_ANNOUNCED','PENDING','2026-01-10T00:00:00Z'),
              (997811,'T1','C_REVOKED','REVOKED','2026-01-10T00:00:00Z');
            -- Workspace B reuses a channel id of workspace A and has its own, later, resume.
            INSERT INTO slack_monitored_channel(workspace_id,slack_team_id,slack_channel_id,consent_state,consent_announced_at) VALUES
              (997812,'T2','C_SHARED','ACTIVE','2026-01-11T00:00:00Z'),
              (997812,'T2','C_ONLY_B','PAUSED','2026-01-11T00:00:00Z');

            INSERT INTO slack_channel_consent_event(workspace_id,slack_channel_id,from_state,to_state,created_at) VALUES
              -- C_SHARED in A: an earlier and a later resume, an activation and a pause that are not resumes.
              (997811,'C_SHARED',NULL,'PENDING','2026-01-09T00:00:00Z'),
              (997811,'C_SHARED','PENDING','ACTIVE','2026-01-30T00:00:00Z'),
              (997811,'C_SHARED','ACTIVE','PAUSED','2026-02-01T00:00:00Z'),
              (997811,'C_SHARED','PAUSED','ACTIVE','2026-02-05T00:00:00Z'),
              (997811,'C_SHARED','ACTIVE','PAUSED','2026-02-10T00:00:00Z'),
              (997811,'C_SHARED','PAUSED','ACTIVE','2026-02-15T00:00:00Z'),
              (997811,'C_SHARED','ACTIVE','PAUSED','2026-03-01T00:00:00Z'),
              (997811,'C_PAUSED_NEVER_RESUMED','PENDING','ACTIVE','2026-01-12T00:00:00Z'),
              (997811,'C_PAUSED_NEVER_RESUMED','ACTIVE','PAUSED','2026-01-13T00:00:00Z'),
              (997811,'C_RESUME_BEFORE_DISCLOSURE','PAUSED','ACTIVE','2026-01-05T00:00:00Z'),
              (997811,'C_ACTIVE_NO_DISCLOSURE','PAUSED','ACTIVE','2026-02-01T00:00:00Z'),
              (997811,'C_REVOKED','PAUSED','ACTIVE','2026-02-01T00:00:00Z'),
              (997811,'C_REVOKED','ACTIVE','REVOKED','2026-02-02T00:00:00Z'),
              -- The same channel id in workspace B, later than every event of A.
              (997812,'C_SHARED','PAUSED','ACTIVE','2026-04-01T00:00:00Z'),
              (997812,'C_ONLY_B','PENDING','ACTIVE','2026-01-12T00:00:00Z'),
              (997812,'C_ONLY_B','ACTIVE','PAUSED','2026-01-13T00:00:00Z');

            -- Outbox rows of the 1.0 schema: a pending post, an email, and an ended post with its receipt.
            INSERT INTO agent_action(id,workspace_id,kind,lane_key,idempotency_key,state,payload,subject_account_id,
                attempts,max_attempts,next_attempt_at,created_at,updated_at)
            VALUES('00000000-0000-7000-8000-000000000001',997811,'SLACK_POST','lane:pending','slack_reply:pending','ADMITTED',
                '{"entryId":"00000000-0000-7000-8000-0000000000aa"}',997813,0,3,now(),now(),now()),
                  ('00000000-0000-7000-8000-000000000002',NULL,'EMAIL','lane:email','email:seeded','ADMITTED',
                '{"kind":"TEST_MESSAGE"}',997813,0,3,now(),now(),now());
            -- A live role-change notice to another recipient that names an account as its actor, as main's migration allows.
            INSERT INTO agent_action(id,workspace_id,kind,lane_key,idempotency_key,state,payload,subject_account_id,
                attempts,max_attempts,next_attempt_at,created_at,updated_at)
            VALUES('00000000-0000-7000-8000-000000000a01',NULL,'EMAIL','lane:role','email:role','ADMITTED',
                '{"kind":"ROLE_CHANGED","event":{"change":{"actorAccountId":997813,"accountId":997814}}}',NULL,0,3,now(),now(),now());
            INSERT INTO agent_action(id,workspace_id,kind,lane_key,idempotency_key,state,payload,subject_account_id,
                attempts,max_attempts,next_attempt_at,created_at,updated_at,ended_at,stop_reason,readback_ref)
            VALUES('00000000-0000-7000-8000-000000000003',997811,'SLACK_POST','lane:done','slack_reply:done','DONE',NULL,
                997813,1,3,now(),now(),now(),now(),'COMPLETED','{"channelId":"D1","ts":"1.1"}');
            """);

        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG);

            // The floor is the later of the disclosure and the latest resume of the same workspace and channel.
            assertThat(floor(database, 997811, "C_SHARED"))
                    .as("the latest resume of this workspace, not the activation, the pause, or B's later resume")
                    .isEqualTo("2026-02-15T00:00:00Z");
            assertThat(floor(database, 997812, "C_SHARED"))
                    .as("workspace B has its own resume")
                    .isEqualTo("2026-04-01T00:00:00Z");
            assertThat(floor(database, 997811, "C_PAUSED_NEVER_RESUMED"))
                    .as("a paused channel that never resumed keeps its disclosure")
                    .isEqualTo("2026-01-10T00:00:00Z");
            assertThat(floor(database, 997811, "C_RESUME_BEFORE_DISCLOSURE"))
                    .as("a resume before the disclosure cannot move the floor back")
                    .isEqualTo("2026-01-20T00:00:00Z");
            assertThat(floor(database, 997812, "C_ONLY_B"))
                    .as("a pause is not a resume")
                    .isEqualTo("2026-01-11T00:00:00Z");

            // No disclosure, no floor: an active or paused channel without one fails closed. The rest never get one.
            for (String channel : new String[] {
                "C_ACTIVE_NO_DISCLOSURE", "C_PAUSED_NO_DISCLOSURE", "C_PENDING", "C_PENDING_ANNOUNCED", "C_REVOKED"
            })
                assertThat(floor(database, 997811, channel))
                        .as("%s has no ingest floor", channel)
                        .isNull();

            // Nothing is invented: no announcement, and consent states and times are as they were.
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT count(*)::text FROM slack_monitored_channel WHERE workspace_id IN (997811,997812)"
                                    + " AND consent_announcement_id IS NOT NULL"))
                    .containsExactly("0");
            for (var expected : Map.ofEntries(
                            Map.entry("997811/C_SHARED", "ACTIVE/2026-01-10T00:00:00Z"),
                            Map.entry("997811/C_PAUSED_NEVER_RESUMED", "PAUSED/2026-01-10T00:00:00Z"),
                            Map.entry("997811/C_RESUME_BEFORE_DISCLOSURE", "ACTIVE/2026-01-20T00:00:00Z"),
                            Map.entry("997811/C_ACTIVE_NO_DISCLOSURE", "ACTIVE/-"),
                            Map.entry("997811/C_PAUSED_NO_DISCLOSURE", "PAUSED/-"),
                            Map.entry("997811/C_PENDING", "PENDING/-"),
                            Map.entry("997811/C_PENDING_ANNOUNCED", "PENDING/2026-01-10T00:00:00Z"),
                            Map.entry("997811/C_REVOKED", "REVOKED/2026-01-10T00:00:00Z"),
                            Map.entry("997812/C_SHARED", "ACTIVE/2026-01-11T00:00:00Z"),
                            Map.entry("997812/C_ONLY_B", "PAUSED/2026-01-11T00:00:00Z"))
                    .entrySet()) {
                int slash = expected.getKey().indexOf('/');
                String workspace = expected.getKey().substring(0, slash);
                String channel = expected.getKey().substring(slash + 1);
                assertThat(PracticeMigrationFixture.column(
                                database,
                                "SELECT consent_state || '/' || coalesce(to_char(consent_announced_at AT TIME ZONE 'UTC',"
                                        + "'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'),'-') FROM slack_monitored_channel WHERE workspace_id="
                                        + workspace + " AND slack_channel_id='" + channel + "'"))
                        .as("consent state and disclosure time of %s are unchanged", expected.getKey())
                        .containsExactly(expected.getValue());
            }
            assertThat(
                            PracticeMigrationFixture.column(
                                    database,
                                    "SELECT count(*)::text FROM slack_channel_consent_event WHERE workspace_id IN (997811,997812)"))
                    .containsExactly("16");

            // Existing outbox rows stay, with no native recipient.
            assertThat(
                            PracticeMigrationFixture.column(
                                    database,
                                    "SELECT id || '/' || kind || '/' || state || '/' || (payload IS NULL)::text || '/' || (native_recipient IS NULL)::text"
                                            + " FROM agent_action WHERE id::text LIKE '00000000-0000-7000-8000-00000000000%' ORDER BY id"))
                    .containsExactly(
                            "00000000-0000-7000-8000-000000000001/SLACK_POST/ADMITTED/false/true",
                            "00000000-0000-7000-8000-000000000002/EMAIL/ADMITTED/false/true",
                            "00000000-0000-7000-8000-000000000003/SLACK_POST/DONE/true/true");

            // The new states and kinds are accepted, and an invalid one is still refused.
            PracticeMigrationFixture.execute(database, """
                    UPDATE slack_monitored_channel SET consent_state='ACTIVATING',
                      consent_announcement_id='00000000-0000-7000-8000-0000000000bb'
                      WHERE workspace_id=997811 AND slack_channel_id='C_PENDING';
                    UPDATE slack_monitored_channel SET consent_state='FAILED'
                      WHERE workspace_id=997811 AND slack_channel_id='C_PENDING_ANNOUNCED';
                    INSERT INTO slack_channel_consent_event(workspace_id,slack_channel_id,from_state,to_state)
                      VALUES(997811,'C_PENDING','PENDING','ACTIVATING'),(997811,'C_PENDING','ACTIVATING','FAILED');
                    INSERT INTO agent_action(id,workspace_id,kind,lane_key,idempotency_key,state,payload,
                        native_recipient,attempts,max_attempts,next_attempt_at,created_at,updated_at)
                    VALUES('00000000-0000-7000-8000-000000000011',997811,'SLACK_NOTICE','lane:notice','notice:1','ADMITTED','{}',
                        '{"providerId":1,"subject":"U1","team":"T1"}',0,3,now(),now(),now()),
                          (uuidv7(),997811,'SLACK_JOIN','lane:join','join:1','ADMITTED','{}',NULL,0,3,now(),now(),now()),
                          (uuidv7(),997811,'SLACK_CONSENT','lane:consent','consent:1','ADMITTED','{}',NULL,0,3,now(),now(),now());
                    """);
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                            database,
                            "UPDATE slack_monitored_channel SET consent_state='UNKNOWN' WHERE workspace_id=997811"
                                    + " AND slack_channel_id='C_REVOKED'"))
                    .hasMessageContaining("chk_slack_monitored_channel_consent");
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                            database,
                            "INSERT INTO agent_action(id,workspace_id,kind,lane_key,idempotency_key,state,payload,attempts,"
                                    + "max_attempts,next_attempt_at,created_at,updated_at) VALUES(uuidv7(),997811,'SLACK_FUTURE',"
                                    + "'lane:x','x:1','ADMITTED','{}',0,3,now(),now(),now())"))
                    .hasMessageContaining("ck_agent_action_kind");

            // The native recipient is part of an action's identity, and it has a partial index for erasure by subject.
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                            database,
                            "UPDATE agent_action SET native_recipient='{\"providerId\":1,\"subject\":\"U2\",\"team\":\"T1\"}'"
                                    + " WHERE id='00000000-0000-7000-8000-000000000011'"))
                    .hasMessageContaining("immutable");
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT pg_get_expr(i.indpred, i.indrelid) FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid"
                                    + " WHERE c.relname='idx_agent_action_native_recipient_subject'"))
                    .singleElement()
                    .asString()
                    .contains("native_recipient IS NOT NULL");
            assertThat(PracticeMigrationFixture.column(
                            database, "SELECT tgenabled::text FROM pg_trigger WHERE tgname='agent_action_lifecycle'"))
                    .containsExactly("O");

            // Main's role-notice redaction still works after this change set replaced the guard function, and only it.
            PracticeMigrationFixture.execute(
                    database,
                    "UPDATE agent_action SET payload = jsonb_set(payload, '{event,change}',"
                            + " redact_role_change(payload #> '{event,change}', 997813))"
                            + " WHERE id='00000000-0000-7000-8000-000000000a01'");
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT (payload #>> '{event,change,actorAccountId}' IS NULL)::text || '/' ||"
                                    + " (payload #>> '{event,change,accountId}')"
                                    + " FROM agent_action WHERE id='00000000-0000-7000-8000-000000000a01'"))
                    .containsExactly("true/997814");
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                            database,
                            "UPDATE agent_action SET payload = jsonb_set(payload, '{event,change,role}', '\"OWNER\"'::jsonb)"
                                    + " WHERE id='00000000-0000-7000-8000-000000000a01'"))
                    .hasMessageContaining("payload changes only to erase role-notice identities");

            assertThatThrownBy(() -> PracticeMigrationFixture.rollbackOnly(liquibase, "prod", CHANGELOG))
                    .hasStackTraceContaining("pre-upgrade database backup");
        }
    }

    private static @Nullable String floor(
            PostgreSQLTestContainer.TestDatabase database, long workspaceId, String channel) {
        return PracticeMigrationFixture.column(
                        database,
                        "SELECT to_char(ingest_after AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"')"
                                + " FROM slack_monitored_channel WHERE workspace_id=" + workspaceId
                                + " AND slack_channel_id='" + channel + "'")
                .getFirst();
    }
}
