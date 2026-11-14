package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("database")
class PracticeV1FeedbackDigestDatabaseTest {
    private static final String WORK = "00000000-0000-0000-0000-000000978041";
    private static final String CHAT = "00000000-0000-0000-0000-000000978044";

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldKeepRepairStateBoundedAndTheConfirmedThreadImmutable(String context) throws Exception {
        var database = NativePracticeFixture.feedbackParent(context);
        PracticeMigrationFixture.execute(database, """
            UPDATE feedback SET composition='{"edits":3}',stop_reason='STEP_LIMIT'
            WHERE id='00000000-0000-0000-0000-000000978041';
            UPDATE feedback_copy SET thread_ref='provider-thread',stop_reason='STEP_LIMIT'
            WHERE id='00000000-0000-0000-0000-000000978042';
            """);
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT composition->>'edits' FROM feedback WHERE id='" + WORK + "'"))
                .containsExactly("3");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                        database, "UPDATE feedback SET composition='{\"edits\":4}' WHERE id='" + WORK + "'"))
                .hasRootCauseInstanceOf(SQLException.class)
                .hasMessageContaining("ck_feedback_composition");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(database, """
            UPDATE feedback_copy SET thread_ref='other-thread'
            WHERE id='00000000-0000-0000-0000-000000978042'
            """))
                .hasRootCauseInstanceOf(SQLException.class)
                .hasMessageContaining("thread binding is immutable");
        assertThat(PracticeMigrationFixture.column(database, """
            SELECT thread_ref FROM feedback_copy WHERE id='00000000-0000-0000-0000-000000978042'
            """)).containsExactly("provider-thread");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldKeepApprovalThroughFallbackAndBindThePreparedWordsAndSide(String context) throws Exception {
        var database = NativePracticeFixture.feedbackParent(context);
        PracticeMigrationFixture.execute(database, """
            INSERT INTO feedback_copy(id,workspace_id,feedback_id,kind,position,body,path,start_line,end_line,side,state,write_key,write_marker)
            VALUES('00000000-0000-0000-0000-000000997171',978001,'00000000-0000-0000-0000-000000978041',
                'LINE_NOTE',1,'Explain the retry.','retry.ts',10,12,'OLD','PLANNED',
                '00000000-0000-0000-0000-000000997171','<!-- hephaestus-feedback-copy:00000000-0000-0000-0000-000000997171 -->');
            """);
        var approved = PracticeMigrationFixture.column(database, "SELECT feedback_content_digest('" + WORK + "')");
        PracticeMigrationFixture.execute(database, """
            UPDATE feedback_copy SET kind='FILE_NOTE',state='POSTED',write_started_at=now(),posted_at=now(),external_ref='provider:456'
            WHERE id='00000000-0000-0000-0000-000000997171';
            """);
        assertThat(PracticeMigrationFixture.column(database, "SELECT feedback_content_digest('" + WORK + "')"))
                .isEqualTo(approved);
        // These changes occur before a fence; admitted receipt facts remain immutable.
        PracticeMigrationFixture.execute(database, """
            INSERT INTO feedback_copy(id,workspace_id,feedback_id,kind,position,body,path,start_line,end_line,side,state,write_key)
            VALUES('00000000-0000-0000-0000-000000997172',978001,'00000000-0000-0000-0000-000000978041',
                'LINE_NOTE',2,'Explain the delay.','retry.ts',20,22,'NEW','PLANNED','00000000-0000-0000-0000-000000997172');
            """);
        var planned = PracticeMigrationFixture.column(database, "SELECT feedback_content_digest('" + WORK + "')");
        assertThat(planned).isNotEqualTo(approved);
        PracticeMigrationFixture.execute(
                database, "UPDATE feedback_copy SET side='OLD' WHERE id='00000000-0000-0000-0000-000000997172'");
        var changedSide = PracticeMigrationFixture.column(database, "SELECT feedback_content_digest('" + WORK + "')");
        assertThat(changedSide).isNotEqualTo(planned);
        PracticeMigrationFixture.execute(
                database,
                "UPDATE feedback_copy SET body='Change the delay.' WHERE id='00000000-0000-0000-0000-000000997172'");
        assertThat(PracticeMigrationFixture.column(database, "SELECT feedback_content_digest('" + WORK + "')"))
                .isNotEqualTo(changedSide);
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldKeepShownConversationWordsSeparateFromThePreparedBrief(String context) throws Exception {
        var database = NativePracticeFixture.feedbackParent(context);
        var prepared = PracticeMigrationFixture.column(database, "SELECT feedback_content_digest('" + CHAT + "')");
        PracticeMigrationFixture.execute(database, """
            UPDATE feedback_copy SET state='WRITE_STARTED',write_started_at=now(),
                conversation_turn_id='00000000-0000-0000-0000-000000978053'
            WHERE id='00000000-0000-0000-0000-000000978045';
            UPDATE conversation_entry SET payload='{"type":"message","id":"native-answer","message":{"role":"assistant",
                "content":[{"type":"toolCall","id":"call-1","name":"show_feedback","arguments":{
                    "feedbackId":"00000000-0000-0000-0000-000000978044","text":"Actual shown words."}}]}}'::jsonb
            WHERE id='00000000-0000-0000-0000-000000978052';
            INSERT INTO conversation_entry(id,workspace_id,conversation_id,seq,kind,surface,text,payload,turn_id,created_at)
            VALUES('00000000-0000-0000-0000-000000978054',978001,'00000000-0000-0000-0000-000000978051',2,
                'TOOL_RESULT','APP','','{"type":"message","id":"r1","message":{"role":"toolResult",
                "toolCallId":"call-1","toolName":"show_feedback","isError":false,
                "content":[{"type":"text","text":"Shown"}]}}'::jsonb,
                '00000000-0000-0000-0000-000000978053',now());
            UPDATE agent_run SET state='ENDED',leased_until=NULL,stop_reason='COMPLETED',ended_at=now()
            WHERE id='00000000-0000-0000-0000-000000978058';
            UPDATE conversation_turn SET status='DONE',finished_at=now() WHERE id='00000000-0000-0000-0000-000000978053';
            UPDATE feedback_copy SET state='POSTED',posted_at=now(),body='Actual shown words.',
                conversation_entry_id='00000000-0000-0000-0000-000000978052'
            WHERE id='00000000-0000-0000-0000-000000978045';
            """);
        assertThat(PracticeMigrationFixture.column(database, "SELECT feedback_content_digest('" + CHAT + "')"))
                .isEqualTo(prepared);
        PracticeMigrationFixture.execute(
                database, "UPDATE feedback SET body='A different prepared brief.' WHERE id='" + CHAT + "'");
        assertThat(PracticeMigrationFixture.column(database, "SELECT feedback_content_digest('" + CHAT + "')"))
                .isNotEqualTo(prepared);
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldHashAllPreparedPiecesInTheirInputOrder(String context) throws Exception {
        var database = NativePracticeFixture.feedbackParent(context);
        assertThat(PracticeMigrationFixture.column(database, """
            SELECT feedback_package_digest(digests) FROM (VALUES
                (1,ARRAY[]::text[]),
                (2,ARRAY[repeat('0',64)]),
                (3,ARRAY[repeat('0',64),repeat('1',64)]),
                (4,ARRAY[repeat('1',64),repeat('0',64)]),
                (5,ARRAY[repeat('0',64),repeat('0',64)])
            ) vectors(position,digests) ORDER BY position
            """))
                .containsExactly(
                        "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945",
                        "a1b93e4c095824efec3a2d5c5fe52d21f876ba16c57a13fd1b3931a7eb22a218",
                        "fb318ac9ca7ca216120530046df407732ab70804f2ea6d6425ac147daf2f7041",
                        "3b695784494a19c90f1c0ecdc394c0c1e638da01f8ca36dea8f285f01877a5dc",
                        "b44dbed3b71e44376453efcfd2d699c24e8b9428b85118f369e77a0d9e3fbf94");
        for (String value : new String[] {"ARRAY[NULL]::text[]", "ARRAY['invalid']", "ARRAY[[repeat('0',64)]]"}) {
            assertThatThrownBy(() ->
                            PracticeMigrationFixture.column(database, "SELECT feedback_package_digest(" + value + ")"))
                    .rootCause()
                    .isInstanceOfSatisfying(
                            SQLException.class,
                            exception -> assertThat(exception.getSQLState()).isEqualTo("23514"));
        }
    }
}
