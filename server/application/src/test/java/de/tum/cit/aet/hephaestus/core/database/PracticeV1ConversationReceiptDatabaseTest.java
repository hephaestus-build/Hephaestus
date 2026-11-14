package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.SQLException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("database")
class PracticeV1ConversationReceiptDatabaseTest {
    private static final String COPY = "00000000-0000-0000-0000-000000998172";
    private static final String TURN = "00000000-0000-0000-0000-000000978053";
    private static final String ANSWER = "00000000-0000-0000-0000-000000998192";
    private static final String OTHER_ANSWER = "00000000-0000-0000-0000-000000978052";
    private static final String FOREIGN_TURN = "00000000-0000-0000-0000-000000998195";
    private static final String FOREIGN_ANSWER = "00000000-0000-0000-0000-000000998194";
    private static final String TOOL_RESULT = "00000000-0000-0000-0000-000000998196";
    private static final String SHOWN = "'Actual shown words.'";

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldBindTheClaimAndRecordOnlyItsCompletedAnswerWords(String context) throws Exception {
        var database = prepared(context);
        reject(
                database,
                "UPDATE feedback_copy SET state='WRITE_STARTED',write_started_at='2026-02-01T00:00:00Z' WHERE id='"
                        + COPY + "'",
                "23514");
        reject(
                database,
                "UPDATE feedback_copy SET state='WRITE_STARTED',write_started_at='2026-02-01T00:00:00Z',"
                        + "conversation_turn_id='" + FOREIGN_TURN + "' WHERE id='" + COPY + "'",
                "23514");
        claim(database);
        var digest = PracticeMigrationFixture.column(
                database, "SELECT feedback_content_digest('00000000-0000-0000-0000-000000998161')");
        reject(
                database,
                "UPDATE feedback_copy SET conversation_turn_id='" + FOREIGN_TURN + "' WHERE id='" + COPY + "'",
                "55000");
        reject(database, "UPDATE feedback_copy SET conversation_turn_id=NULL WHERE id='" + COPY + "'", "55000");
        reject(
                database,
                "UPDATE feedback_copy SET conversation_entry_id='" + FOREIGN_ANSWER + "' WHERE id='" + COPY + "'",
                "23514");
        PracticeMigrationFixture.execute(
                database, "UPDATE feedback_copy SET conversation_entry_id='" + ANSWER + "' WHERE id='" + COPY + "'");
        reject(
                database,
                "UPDATE feedback_copy SET conversation_entry_id='" + OTHER_ANSWER + "' WHERE id='" + COPY + "'",
                "55000");
        reject(database, "UPDATE feedback_copy SET conversation_entry_id=NULL WHERE id='" + COPY + "'", "55000");
        reject(database, "UPDATE feedback_copy SET body='Prepared brief.' WHERE id='" + COPY + "'", "55000");
        answer(database);
        // The turn is still running, so its answer is not complete.
        reject(database, receipt(SHOWN), "23514");
        complete(database);
        reject(database, receipt("'Some context.'"), "23514");
        // The answer's own words are not the words its show_feedback call showed.
        reject(database, receipt("E'Some context.\\n\\nActual shown words.'"), "23514");
        reject(database, receipt("'Prepared brief.'"), "23514");
        // A failed show_feedback call shows nothing.
        PracticeMigrationFixture.execute(
                database,
                "UPDATE conversation_entry SET payload=jsonb_set(payload,'{message,isError}','true')" + " WHERE id='"
                        + TOOL_RESULT + "'");
        reject(database, receipt(SHOWN), "23514");
        PracticeMigrationFixture.execute(
                database,
                "UPDATE conversation_entry SET payload=jsonb_set(payload,'{message,isError}','false')" + " WHERE id='"
                        + TOOL_RESULT + "'");
        reject(
                database,
                "UPDATE feedback_copy SET state='POSTED',posted_at='2026-02-01T00:01:00Z'" + " WHERE id='" + COPY + "'",
                "23514");
        reject(
                database,
                "UPDATE feedback_copy SET state='POSTED',body=" + SHOWN + ","
                        + "posted_at='2026-01-01T00:01:00Z' WHERE id='" + COPY + "'",
                "55000");
        PracticeMigrationFixture.execute(
                database, "UPDATE conversation_entry SET redacted_at='2026-02-01T00:00:30Z' WHERE id='" + ANSWER + "'");
        reject(database, receipt(SHOWN), "23514");
        PracticeMigrationFixture.execute(
                database, "UPDATE conversation_entry SET redacted_at=NULL WHERE id='" + ANSWER + "'");
        // A failed transport can later supply confirmation, without another claim or a new fence.
        PracticeMigrationFixture.execute(database, "UPDATE feedback_copy SET state='FAILED' WHERE id='" + COPY + "'");
        PracticeMigrationFixture.execute(database, receipt(SHOWN));
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT state || '|' || (body=" + SHOWN + ")::text || '|' || conversation_turn_id || '|' ||"
                                + " conversation_entry_id || '|' ||"
                                + " (write_started_at='2026-02-01T00:00:00Z'::timestamptz)::text"
                                + " FROM feedback_copy WHERE id='" + COPY + "'"))
                .containsExactly("POSTED|true|" + TURN + "|" + ANSWER + "|true");
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT feedback_content_digest('00000000-0000-0000-0000-000000998161')"))
                .isEqualTo(digest);
        reject(database, "UPDATE feedback_copy SET body='Different words.' WHERE id='" + COPY + "'", "55000");
        reject(database, "UPDATE feedback_copy SET body=NULL WHERE id='" + COPY + "'", "55000");
        reject(
                database,
                "UPDATE feedback_copy SET write_started_at=write_started_at+interval '1 second'" + " WHERE id='" + COPY
                        + "'",
                "55000");
        reject(database, "UPDATE feedback_copy SET write_key='another-key' WHERE id='" + COPY + "'", "55000");
        reject(database, "UPDATE feedback_copy SET write_marker='another-marker' WHERE id='" + COPY + "'", "55000");
        reject(
                database,
                "UPDATE feedback_copy SET posted_at=posted_at+interval '1 second' WHERE id='" + COPY + "'",
                "55000");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT count(*)::text FROM agent_action WHERE"
                                + " lane_key='feedback:00000000-0000-0000-0000-000000998161'"))
                .containsExactly("0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldKeepReceiptCustodyWhenTheBoundAnswerAndTurnAreDeleted(String context) throws Exception {
        var database = prepared(context);
        claim(database);
        answer(database);
        complete(database);
        PracticeMigrationFixture.execute(
                database,
                "UPDATE feedback_copy SET state='POSTED',posted_at='2026-02-01T00:01:00Z',body=" + SHOWN
                        + ",conversation_entry_id='" + ANSWER + "' WHERE id='" + COPY + "'");
        PracticeMigrationFixture.execute(database, "DELETE FROM conversation_entry WHERE id='" + ANSWER + "'");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT (conversation_entry_id IS NULL)::text || '|' || conversation_turn_id || '|' || state || '|' ||"
                                + " (body=" + SHOWN + ")::text || '|' ||"
                                + " (write_started_at IS NOT NULL AND posted_at IS NOT NULL)::text"
                                + " FROM feedback_copy WHERE id='" + COPY + "'"))
                .containsExactly("true|" + TURN + "|POSTED|true|true");
        reject(
                database,
                "UPDATE feedback_copy SET conversation_entry_id='" + OTHER_ANSWER + "' WHERE id='" + COPY + "'",
                "55000");
        PracticeMigrationFixture.execute(database, "DELETE FROM conversation_turn WHERE id='" + TURN + "'");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT (conversation_entry_id IS NULL AND conversation_turn_id IS NULL)::text || '|' || state || '|' ||"
                                + " (body=" + SHOWN + ")::text || '|' ||"
                                + " (write_started_at IS NOT NULL AND posted_at IS NOT NULL)::text"
                                + " FROM feedback_copy WHERE id='" + COPY + "'"))
                .containsExactly("true|POSTED|true|true");
        reject(
                database,
                "UPDATE feedback_copy SET conversation_entry_id='" + OTHER_ANSWER + "' WHERE id='" + COPY + "'",
                "55000");
        reject(
                database,
                "UPDATE feedback_copy SET conversation_turn_id='" + FOREIGN_TURN + "' WHERE id='" + COPY + "'",
                "55000");
        reject(
                database,
                "UPDATE feedback_copy SET write_started_at=NULL,state='PLANNED' WHERE id='" + COPY + "'",
                "55000");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldKeepOnWorkWordsAndAnchorsImmutableAfterTheFence(String context) throws Exception {
        var database = prepared(context);
        PracticeMigrationFixture.execute(database, """
            INSERT INTO feedback_copy(id,workspace_id,feedback_id,kind,position,body,path,start_line,end_line,side,state,write_key,write_marker)
            VALUES('00000000-0000-0000-0000-000000998173',978001,'00000000-0000-0000-0000-000000978041',
                'LINE_NOTE',1,'Explain the retry.','retry.ts',10,12,'NEW','PLANNED','fixed-note-key','fixed-note-marker');
            UPDATE feedback_copy SET state='WRITE_STARTED',write_started_at='2026-02-01T00:00:00Z'
            WHERE id='00000000-0000-0000-0000-000000998173';
            """);
        for (String change : new String[] {
            "body='Other words.'",
            "path='other.ts'",
            "start_line=11",
            "end_line=13",
            "side='OLD'",
            "write_key='other-key'",
            "write_marker='other-marker'"
        }) {
            reject(
                    database,
                    "UPDATE feedback_copy SET " + change + " WHERE id='00000000-0000-0000-0000-000000998173'",
                    "55000");
        }
        PracticeMigrationFixture.execute(
                database,
                "UPDATE feedback_copy SET kind='FILE_NOTE',"
                        + "state='POSTED',posted_at='2026-02-01T00:01:00Z',external_ref='confirmed-note'"
                        + " WHERE id='00000000-0000-0000-0000-000000998173'");
    }

    private static TestDatabase prepared(String context) throws Exception {
        var database = NativePracticeFixture.feedbackParent(context);
        PracticeMigrationFixture.execute(database, """
            INSERT INTO feedback(id,workspace_id,review_id,item_id,developer_id,destination,state,body)
            SELECT '00000000-0000-0000-0000-000000998161',workspace_id,review_id,item_id,developer_id,
                'CONVERSATION','READY','Prepared brief.' FROM feedback WHERE id='00000000-0000-0000-0000-000000978044';
            INSERT INTO feedback_copy(id,workspace_id,feedback_id,kind,position,state,write_key,write_marker)
            VALUES('00000000-0000-0000-0000-000000998172',978001,'00000000-0000-0000-0000-000000998161',
                'CHAT_TURN',0,'PLANNED','fixed-chat-key','fixed-chat-marker');
            INSERT INTO conversation_entry(id,workspace_id,conversation_id,seq,kind,surface,text,payload,turn_id,created_at)
            VALUES('00000000-0000-0000-0000-000000998192',978001,'00000000-0000-0000-0000-000000978051',2,'ANSWER','APP',
                '',NULL,'00000000-0000-0000-0000-000000978053','2026-02-01T00:00:00Z');
            INSERT INTO conversation(id,workspace_id,account_id,created_at,last_activity_at)
            VALUES('00000000-0000-0000-0000-000000998193',978001,978007,'2026-02-01T00:00:00Z','2026-02-01T00:00:00Z');
            INSERT INTO agent_run(id,workspace_id,kind,band,concurrency_key,state,attempt,lease_epoch,leased_until,
                available_at,start_within_seconds,created_at,started_at)
            VALUES('00000000-0000-0000-0000-000000998196',978001,'TURN','INTERACTIVE',
                'conversation:00000000-0000-0000-0000-000000998193','LEASED',0,1,now()+interval '5 minutes',
                '2026-02-01T00:00:00Z',180,'2026-02-01T00:00:00Z','2026-02-01T00:00:00Z');
            INSERT INTO conversation_turn(id,workspace_id,conversation_id,request_id,status,surface,attempt,run_id,started_at,
                created_at)
            VALUES('00000000-0000-0000-0000-000000998195',978001,'00000000-0000-0000-0000-000000998193',
                'foreign-turn','RUNNING','APP',1,'00000000-0000-0000-0000-000000998196','2026-02-01T00:00:00Z',
                '2026-02-01T00:00:00Z');
            INSERT INTO conversation_entry(id,workspace_id,conversation_id,seq,kind,surface,text,turn_id,created_at)
            VALUES('00000000-0000-0000-0000-000000998194',978001,'00000000-0000-0000-0000-000000998193',1,'ANSWER','APP',
                'Another developer.','00000000-0000-0000-0000-000000998195','2026-02-01T00:00:00Z');
            """);
        return database;
    }

    private static void claim(TestDatabase database) throws SQLException {
        PracticeMigrationFixture.execute(
                database,
                "UPDATE feedback_copy SET state='WRITE_STARTED',"
                        + "write_started_at='2026-02-01T00:00:00Z',conversation_turn_id='" + TURN + "' WHERE id='"
                        + COPY
                        + "'");
    }

    /**
     * Hephaestus's answer: its own words, its show_feedback calls for this and another feedback, and the successful result
     * of the call for this feedback.
     */
    private static void answer(TestDatabase database) {
        PracticeMigrationFixture.execute(database, """
            UPDATE conversation_entry SET text='Some context.',payload='{"type":"message","id":"a1","message":{
                "role":"assistant","content":[{"type":"text","text":"Some context."},
                {"type":"toolCall","id":"call-1","name":"show_feedback","arguments":{
                    "feedbackId":"00000000-0000-0000-0000-000000998161","text":"Actual shown words."}},
                {"type":"toolCall","id":"call-2","name":"show_feedback","arguments":{
                    "feedbackId":"00000000-0000-0000-0000-000000978044","text":"Other feedback."}}]}}'::jsonb
            WHERE id='00000000-0000-0000-0000-000000998192';
            INSERT INTO conversation_entry(id,workspace_id,conversation_id,seq,kind,surface,text,payload,turn_id,created_at)
            VALUES('00000000-0000-0000-0000-000000998196',978001,'00000000-0000-0000-0000-000000978051',3,
                'TOOL_RESULT','APP','','{"type":"message","id":"r1","message":{"role":"toolResult",
                "toolCallId":"call-1","toolName":"show_feedback","isError":false,
                "content":[{"type":"text","text":"Shown"}]}}'::jsonb,
                '00000000-0000-0000-0000-000000978053','2026-02-01T00:00:00Z');
            """);
    }

    private static void complete(TestDatabase database) {
        PracticeMigrationFixture.execute(
                database,
                "UPDATE agent_run SET state='ENDED',leased_until=NULL,stop_reason='COMPLETED',ended_at=now()"
                        + " WHERE id=(SELECT run_id FROM conversation_turn WHERE id='" + TURN + "');"
                        + " UPDATE conversation_turn SET status='DONE',finished_at='2026-02-01T00:00:50Z' WHERE id='"
                        + TURN
                        + "'");
    }

    private static String receipt(String body) {
        return "UPDATE feedback_copy SET state='POSTED',posted_at='2026-02-01T00:01:00Z',body=" + body + " WHERE id='"
                + COPY + "'";
    }

    private static void reject(TestDatabase database, String sql, String sqlState) {
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(database, sql))
                .rootCause()
                .isInstanceOfSatisfying(
                        SQLException.class,
                        exception -> assertThat(exception.getSQLState()).isEqualTo(sqlState));
    }
}
