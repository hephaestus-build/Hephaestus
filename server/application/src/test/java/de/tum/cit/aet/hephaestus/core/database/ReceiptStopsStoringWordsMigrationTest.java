package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.DriverManager;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The upgrade clears the words that a conversation receipt of a server stored, and keeps the receipt: its answer shows
 * them. Clearing them expires the owner's ready export. A receipt that the 1.0 upgrade brought from 0.x has no answer
 * and no write fence, so it keeps its words, their only home, and its owner keeps a ready export. The guard change rolls
 * back exactly; the data correction stops a rollback, because the cleared words cannot come back.
 */
@Tag("database")
class ReceiptStopsStoringWordsMigrationTest {

    private static final String CHANGELOG = "-fix-receipt-stops-storing-words.xml";
    private static final String COPY = "00000000-0000-0000-0000-000000978045";
    private static final String TURN = "00000000-0000-0000-0000-000000978053";
    private static final String ANSWER = "00000000-0000-0000-0000-000000979501";
    private static final String MIGRATED = "00000000-0000-0000-0000-000000978047";
    private static final String GUARD = "SELECT pg_get_functiondef('public.guard_feedback_copy_fence()'::regprocedure)";
    private static final String RECEIPT = "SELECT state || '|' || coalesce(body,'-') || '|'"
            + " || (posted_at='2026-02-01T00:01:00Z'::timestamptz AND write_started_at='2026-02-01T00:00:00Z'::timestamptz)"
            + " || '|' || conversation_turn_id || '|' || conversation_entry_id FROM feedback_copy WHERE id='" + COPY
            + "'";
    private static final String MIGRATED_WORDS =
            "SELECT state || '|' || coalesce(body,'-') FROM feedback_copy WHERE id='" + MIGRATED + "'";
    private static final String EXPORTS =
            "SELECT account_id || ':' || status FROM account_export WHERE account_id IN (978003,978007) ORDER BY account_id";

    @Test
    void shouldClearAServersReceiptWordsKeepAMigratedReceiptsAndRollBackOnlyTheGuard() throws Exception {
        String name = "receipt_words_" + UUID.randomUUID().toString().replace("-", "");
        var database = PostgreSQLTestContainer.createDatabase(name);
        PostgreSQLTestContainer.applyDatabaseRoles(name);
        var owner = PostgreSQLTestContainer.asOwner(database);
        PracticeMigrationFixture.upgradeBefore(owner, "prod", CHANGELOG);
        NativePracticeFixture.seedFeedbackParent(owner);
        postWithWords(owner);
        PracticeMigrationFixture.execute(
                owner,
                "UPDATE feedback SET state='DELIVERED',delivered_at='2026-02-01T00:01:00Z'"
                        + " WHERE id='00000000-0000-0000-0000-000000978044'");
        migratedReceipt(owner);
        readyExport(owner, 978003);
        readyExport(owner, 978007);
        assertThat(PracticeMigrationFixture.column(owner, RECEIPT))
                .as("the earlier guard let the receipt keep the words that its answer showed")
                .containsExactly("POSTED|Actual shown words.|true|" + TURN + "|" + ANSWER);
        var earlierGuard = PracticeMigrationFixture.column(owner, GUARD);

        try (var connection = DriverManager.getConnection(owner.jdbcUrl(), owner.username(), owner.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG);

            assertThat(PracticeMigrationFixture.column(owner, RECEIPT))
                    .as("the receipt keeps its state, time and answer, and loses its words")
                    .containsExactly("POSTED|-|true|" + TURN + "|" + ANSWER);
            assertThat(PracticeMigrationFixture.column(
                            owner,
                            "SELECT conversation_receipt_words(workspace_id,feedback_id,conversation_entry_id,"
                                    + "conversation_turn_id) FROM feedback_copy WHERE id='" + COPY + "'"))
                    .as("the answer still shows the words")
                    .containsExactly("Actual shown words.");
            assertThat(PracticeMigrationFixture.column(owner, MIGRATED_WORDS))
                    .as("a receipt that the 1.0 upgrade brought from 0.x keeps its words, their only home")
                    .containsExactly("POSTED|Words that the upgrade kept.");
            assertThat(PracticeMigrationFixture.column(owner, EXPORTS))
                    .as("clearing a receipt's words expires its owner's ready export, and only that one")
                    .containsExactly("978003:EXPIRED", "978007:READY");
            assertThat(PracticeMigrationFixture.column(owner, GUARD)).isNotEqualTo(earlierGuard);

            liquibase.rollback(1, new Contexts("prod"), new LabelExpression());

            assertThat(PracticeMigrationFixture.column(owner, GUARD))
                    .as("the rollback of the guard change restores the earlier guard exactly")
                    .isEqualTo(earlierGuard);
            assertThatThrownBy(() -> liquibase.rollback(1, new Contexts("prod"), new LabelExpression()))
                    .as("the data correction cannot be rolled back")
                    .isInstanceOf(LiquibaseException.class);
            assertThat(PracticeMigrationFixture.column(owner, RECEIPT))
                    .as("the cleared words do not come back")
                    .containsExactly("POSTED|-|true|" + TURN + "|" + ANSWER);
        }
    }

    /** Delivered feedback of another developer whose only receipt the 1.0 upgrade wrote from 0.x. */
    private static void migratedReceipt(PostgreSQLTestContainer.TestDatabase database) {
        PracticeMigrationFixture.execute(database, """
            INSERT INTO feedback(id,workspace_id,review_id,item_id,person_account_id,destination,state,body,created_at,delivered_at)
            VALUES('00000000-0000-0000-0000-000000978048',978001,'00000000-0000-0000-0000-000000978011',978006,978007,
                'CONVERSATION','DELIVERED','Migrated brief.','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z');
            INSERT INTO feedback_copy(id,workspace_id,feedback_id,kind,position,body,state,created_at,posted_at)
            VALUES('00000000-0000-0000-0000-000000978047',978001,'00000000-0000-0000-0000-000000978048',
                'CHAT_TURN',0,'Words that the upgrade kept.','POSTED','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z');
            """);
    }

    /** A ready account export, published under its owner's export fence as the export worker publishes it. */
    private static void readyExport(PostgreSQLTestContainer.TestDatabase database, long account) {
        PracticeMigrationFixture.execute(
                database,
                "WITH fence AS MATERIALIZED (SELECT pg_advisory_xact_lock(hashtext('account-export-sources'),"
                        + " hashtext(CAST(" + account + " AS text)) & 63) AS taken)"
                        + " INSERT INTO account_export(account_id,status,expires_at,payload)"
                        + " SELECT " + account + ",'READY',now() + interval '1 day','\\x01' FROM fence");
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
