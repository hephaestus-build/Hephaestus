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

/**
 * The upgrade that gives people an authorization generation (ADR 0134): work queued before it names the person it
 * depends on, so turning AI off refuses it, and a rollback refuses before it removes anything once an action recorded
 * that its access changed.
 */
@Tag("database")
class AuthoritySendFenceMigrationTest {

    private static final String CHANGELOG = "-feat-authority-send-fence.xml";
    private static final long WORKSPACE = 979301;
    private static final long DEVELOPER = 979302;
    private static final long ASKER = 979303;

    @Test
    void shouldStampQueuedWorkWithItsPersonAndRefuseARollbackBeforeItRemovesAnything() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase("authority_send_fence");
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        String conversation = UUID.randomUUID().toString();
        String turnRun = UUID.randomUUID().toString();
        String reviewRun = UUID.randomUUID().toString();
        PracticeMigrationFixture.execute(
                database, """
                INSERT INTO workspace(id, slug, display_name, account_login, account_type, status)
                VALUES (%1$d, 'w%1$d', 'W%1$d', 'w%1$d', 'ORG', 'ACTIVE');
                INSERT INTO account(id, display_name) VALUES (%2$d, 'Developer'), (%3$d, 'Asker');
                INSERT INTO conversation(id, workspace_id, account_id, created_at, last_activity_at)
                VALUES ('%4$s', %1$d, %3$d, now(), now());
                BEGIN;
                INSERT INTO agent_run(id, workspace_id, kind, band, concurrency_key, state, attempt, lease_epoch)
                VALUES ('%5$s', %1$d, 'TURN', 'INTERACTIVE', 'conversation:%4$s', 'QUEUED', 0, 0),
                       ('%6$s', %1$d, 'REVIEW', 'BATCH', 'review:%6$s', 'QUEUED', 0, 0);
                INSERT INTO conversation_turn(id, workspace_id, conversation_id, account_id, request_id, surface,
                    status, attempt, run_id, created_at, queued_at)
                VALUES (gen_random_uuid(), %1$d, '%4$s', %3$d, 'request-1', 'APP', 'QUEUED', 0, '%5$s', now(), now());
                COMMIT;
                INSERT INTO agent_action(id, workspace_id, kind, lane_key, idempotency_key, state, max_attempts,
                    next_attempt_at, payload, subject_account_id, created_at, updated_at)
                VALUES (gen_random_uuid(), %1$d, 'DELIVER_FEEDBACK', 'feedback:a', 'feedback:a:1:POST', 'ADMITTED', 3,
                        now(), '{}', %2$d, now(), now()),
                       (gen_random_uuid(), %1$d, 'SLACK_POST', 'reply:b', 'reply:b', 'ADMITTED', 3,
                        now(), '{}', %3$d, now(), now()),
                       (gen_random_uuid(), %1$d, 'SLACK_NOTICE', 'notice:c', 'notice:c', 'ADMITTED', 3,
                        now(), '{}', %3$d, now(), now()),
                       (gen_random_uuid(), %1$d, 'EMAIL', 'email:d', 'email:weekly_digest:d', 'ADMITTED', 3,
                        now(), '{"kind": "WEEKLY_DIGEST", "event": {}}', %3$d, now(), now()),
                       (gen_random_uuid(), %1$d, 'EMAIL', 'email:e', 'email:role_changed:e', 'ADMITTED', 3,
                        now(), '{"kind": "ROLE_CHANGED", "event": {}}', %3$d, now(), now());
                """.formatted(WORKSPACE, DEVELOPER, ASKER, conversation, turnRun, reviewRun));

        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG);

            assertThat(PracticeMigrationFixture.column(database, """
                    SELECT kind || ' ' || coalesce(payload ->> 'kind', '-') || ' ' || authorization_stamp::text
                    FROM agent_action WHERE workspace_id = %d ORDER BY kind, payload ->> 'kind'
                    """.formatted(WORKSPACE)))
                    .as("a queued send names the person it depends on; a notice depends on no one's AI choice")
                    .containsExactly(
                            "DELIVER_FEEDBACK - {\"accounts\": {\"%d\": 0}, \"workspace\": 0}".formatted(DEVELOPER),
                            "EMAIL ROLE_CHANGED {\"workspace\": 0}",
                            "EMAIL WEEKLY_DIGEST {\"accounts\": {\"%d\": 0}, \"workspace\": 0}".formatted(ASKER),
                            "SLACK_NOTICE - {\"workspace\": 0}",
                            "SLACK_POST - {\"accounts\": {\"%d\": 0}, \"workspace\": 0}".formatted(ASKER));
            assertThat(PracticeMigrationFixture.column(database, """
                    SELECT kind || ' ' || authorization_stamp::text FROM agent_run
                    WHERE workspace_id = %d ORDER BY kind
                    """.formatted(WORKSPACE)))
                    .as("a queued turn names its conversation's owner")
                    .containsExactly(
                            "REVIEW {\"workspace\": 0}",
                            "TURN {\"accounts\": {\"%d\": 0}, \"workspace\": 0}".formatted(ASKER));

            // Turning AI off now raises the person's generation, so the stamped send is refused.
            PracticeMigrationFixture.execute(
                    database, "UPDATE account SET ai_choice = 'NO_AI', ai_choice_at = now() WHERE id = " + DEVELOPER);
            assertThat(PracticeMigrationFixture.column(
                            database, "SELECT authority_generation FROM account WHERE id = " + DEVELOPER))
                    .containsExactly("1");

            PracticeMigrationFixture.execute(database, """
                    UPDATE agent_action SET state = 'CANCELLED', stop_reason = 'AUTHORITY_CHANGED', ended_at = now(),
                        payload = NULL
                    WHERE workspace_id = %d AND kind = 'DELIVER_FEEDBACK'
                    """.formatted(WORKSPACE));
            assertThatThrownBy(() -> PracticeMigrationFixture.rollbackOnly(liquibase, "prod", CHANGELOG))
                    .hasMessageContaining("Restore the verified pre-upgrade backup");
            assertThat(PracticeMigrationFixture.column(database, """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_name = 'account' AND column_name = 'authority_generation'
                    """))
                    .as("the refused rollback removed nothing")
                    .containsExactly("1");
        }
    }
}
