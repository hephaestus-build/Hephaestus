package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

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
 * The upgrade widens the ready-export trigger to the show_feedback result that a receipt's words need, and leaves the
 * receipt as it is: a server of the previous release still reads its words while the upgrade rolls. The rollback
 * restores the earlier trigger exactly.
 */
@Tag("database")
class ShownFeedbackExportTriggerMigrationTest {

    private static final String CHANGELOG = "-fix-export-expires-when-shown-feedback-is-erased.xml";
    private static final String COPY = "00000000-0000-0000-0000-000000978045";
    private static final String TURN = "00000000-0000-0000-0000-000000978053";
    private static final String ANSWER = "00000000-0000-0000-0000-000000979501";
    private static final String EXPORT_TRIGGER =
            "SELECT pg_get_triggerdef(oid) FROM pg_trigger" + " WHERE tgname='trg_conversation_entry_changed_exports'";
    private static final String RECEIPT = "SELECT state || '|' || coalesce(body,'-') || '|'"
            + " || (posted_at='2026-02-01T00:01:00Z'::timestamptz AND write_started_at='2026-02-01T00:00:00Z'::timestamptz)"
            + " || '|' || conversation_turn_id || '|' || conversation_entry_id FROM feedback_copy WHERE id='" + COPY
            + "'";
    private static final String POSTED = "POSTED|Actual shown words.|true|" + TURN + "|" + ANSWER;

    @Test
    void shouldWidenTheExportTriggerKeepTheReceiptAndRollBackExactly() throws Exception {
        String name = "shown_feedback_export_" + UUID.randomUUID().toString().replace("-", "");
        var database = PostgreSQLTestContainer.createDatabase(name);
        PostgreSQLTestContainer.applyDatabaseRoles(name);
        var owner = PostgreSQLTestContainer.asOwner(database);
        PracticeMigrationFixture.upgradeBefore(owner, "prod", CHANGELOG);
        NativePracticeFixture.seedFeedbackParent(owner);
        postWithWords(owner);
        var earlierTrigger = PracticeMigrationFixture.column(owner, EXPORT_TRIGGER);

        try (var connection = DriverManager.getConnection(owner.jdbcUrl(), owner.username(), owner.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG);

            assertThat(PracticeMigrationFixture.column(owner, RECEIPT))
                    .as("the receipt keeps its words for a server of the previous release")
                    .containsExactly(POSTED);
            assertThat(PracticeMigrationFixture.column(owner, EXPORT_TRIGGER))
                    .singleElement()
                    .asString()
                    .contains("show_feedback");

            PracticeMigrationFixture.rollbackOnly(liquibase, "prod", CHANGELOG);

            assertThat(PracticeMigrationFixture.column(owner, EXPORT_TRIGGER))
                    .as("the rollback restores the earlier export trigger exactly")
                    .isEqualTo(earlierTrigger);
            assertThat(PracticeMigrationFixture.column(owner, RECEIPT)).containsExactly(POSTED);
        }
    }

    /** The claim, the completed answer and the receipt with its words, through the guard of the earlier schema. */
    private static void postWithWords(PostgreSQLTestContainer.TestDatabase database) {
        PracticeMigrationFixture.execute(
                database,
                "UPDATE feedback_copy SET state='WRITE_STARTED',write_started_at='2026-02-01T00:00:00Z',"
                        + "conversation_turn_id='" + TURN + "' WHERE id='" + COPY + "'");
        PracticeMigrationFixture.execute(database, """
            INSERT INTO conversation_entry(id,workspace_id,conversation_id,account_id,seq,kind,surface,text,payload,turn_id,created_at)
            VALUES('00000000-0000-0000-0000-000000979501',978001,'00000000-0000-0000-0000-000000978051',978003,10,
                'ANSWER','APP','Some context.','{"type":"message","id":"a1","message":{"role":"assistant","content":[
                {"type":"text","text":"Some context."},{"type":"toolCall","id":"call-1","name":"show_feedback",
                "arguments":{"feedbackId":"00000000-0000-0000-0000-000000978044","text":"Actual shown words."}}]}}'::jsonb,
                '00000000-0000-0000-0000-000000978053','2026-02-01T00:00:00Z'),
              ('00000000-0000-0000-0000-000000979502',978001,'00000000-0000-0000-0000-000000978051',978003,11,
                'TOOL_RESULT','APP','','{"type":"message","id":"r1","message":{"role":"toolResult",
                "toolCallId":"call-1","toolName":"show_feedback","isError":false,
                "content":[{"type":"text","text":"Shown"}]}}'::jsonb,
                '00000000-0000-0000-0000-000000978053','2026-02-01T00:00:00Z');
            UPDATE agent_run SET state='ENDED',leased_until=NULL,stop_reason='COMPLETED',ended_at=now()
            WHERE id=(SELECT run_id FROM conversation_turn WHERE id='00000000-0000-0000-0000-000000978053');
            UPDATE conversation_turn SET status='DONE',finished_at='2026-02-01T00:00:50Z'
            WHERE id='00000000-0000-0000-0000-000000978053';
            """);
        PracticeMigrationFixture.execute(
                database, "UPDATE feedback_copy SET conversation_entry_id='" + ANSWER + "' WHERE id='" + COPY + "'");
        PracticeMigrationFixture.execute(
                database,
                "UPDATE feedback_copy SET state='POSTED',posted_at='2026-02-01T00:01:00Z',body='Actual shown words.'"
                        + " WHERE id='" + COPY + "'");
    }
}
