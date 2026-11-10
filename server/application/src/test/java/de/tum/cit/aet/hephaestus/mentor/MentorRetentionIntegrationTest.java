package de.tum.cit.aet.hephaestus.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.slack.retention.SlackRetentionSweeper;
import de.tum.cit.aet.hephaestus.integration.slack.retention.SlackWorkspaceContentEraser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Both retention limits of a conversation on real rows: every entry goes after the conversation retention, and a tool
 * result that could have copied a channel message goes with the Slack retention.
 */
class MentorRetentionIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorRetentionSweeper conversationRetention;

    @Autowired
    private SlackRetentionSweeper slackRetention;

    @Autowired
    private SlackWorkspaceContentEraser slackContent;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private JdbcTemplate jdbc;

    private Workspace workspace;
    private MentorConversation main;
    private Workspace otherWorkspace;
    private MentorConversation otherMain;
    private long seq;

    @BeforeEach
    void setUpConversations() {
        User developer = persistUser("heph-retention-developer");
        workspace = createWorkspace("heph-retention", "Hephaestus", "heph-retention-org", AccountType.ORG, developer);
        main = conversations.findOrCreateMain(workspace.getId(), accountId(developer));
        User other = persistUser("heph-retention-other");
        otherWorkspace =
                createWorkspace("heph-retention-other", "Other", "heph-retention-other-org", AccountType.ORG, other);
        otherMain = conversations.findOrCreateMain(otherWorkspace.getId(), accountId(other));
    }

    private void connectSlack(Workspace connected) {
        connectSlack(connected, IntegrationState.ACTIVE);
    }

    private void connectSlack(Workspace connected, IntegrationState state) {
        Connection connection = connections.saveAndFlush(new Connection(
                connected,
                IntegrationKind.SLACK,
                "T-" + connected.getId(),
                new ConnectionConfig.SlackConfig("T-" + connected.getId(), null, null, Set.of())));
        connectionService.transition(
                connection,
                new ConnectionService.TransitionRequest(
                        IntegrationState.ACTIVE,
                        "TEST_CONNECTED",
                        "SYSTEM",
                        "T-" + connected.getId(),
                        UUID.randomUUID().toString(),
                        "Slack connected"));
        if (state != IntegrationState.ACTIVE) {
            connectionService.transition(
                    connections.findById(connection.getId()).orElseThrow(),
                    new ConnectionService.TransitionRequest(
                            state,
                            "TEST_SUSPENDED",
                            "SYSTEM",
                            "T-" + connected.getId(),
                            UUID.randomUUID().toString(),
                            "Slack suspended"));
        }
    }

    /** A summary of the conversation over its entries so far, written now. */
    private UUID summary(MentorConversation conversation) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mentor_entry (id, workspace_id, conversation_id, seq, kind, surface, text, payload,
                                          created_at)
                VALUES (?, ?, ?, ?, 'SUMMARY', 'APP', 'words-summary',
                        jsonb_build_object('type', 'compaction', 'id', 'pi-' || ?, 'summary', 'words-summary',
                            'fromSeq', 1, 'toSeq', ?),
                        now())
                """, id, conversation.getWorkspaceId(), conversation.getId(), seq + 1, id.toString(), seq);
        seq++;
        return id;
    }

    /** One entry of the conversation, written {@code age} ago; a tool result names the tool that produced it. */
    private UUID entry(MentorConversation conversation, String kind, String tool, Duration age, String words) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO mentor_entry (id, workspace_id, conversation_id, seq, kind, surface, text, payload,
                                          created_at)
                VALUES (?, ?, ?, ?, ?, 'APP', ?,
                        jsonb_build_object('type', 'message', 'id', 'pi-' || ?, 'message',
                            jsonb_build_object('role', CASE WHEN ? = 'TOOL_RESULT' THEN 'toolResult' ELSE 'user' END,
                                'toolName', ?, 'toolCallId', 'call-' || ?,
                                'content', jsonb_build_array(jsonb_build_object('type', 'text', 'text', ?)))),
                        ?)
                """,
                id,
                conversation.getWorkspaceId(),
                conversation.getId(),
                ++seq,
                kind,
                kind.equals("TOOL_RESULT") ? "" : words,
                id.toString(),
                kind,
                tool,
                id.toString(),
                words,
                Timestamp.from(Instant.now().minus(age)));
        return id;
    }

    private boolean erased(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT redacted_at IS NOT NULL AND COALESCE(payload::text, '') NOT LIKE '%words-%' FROM mentor_entry"
                        + " WHERE id = ?",
                Boolean.class, id));
    }

    @Test
    void shouldEraseEveryEntryInTheTwoWeeksBeforeTheConversationRetentionAndKeepTheRest() {
        UUID oldQuestion = entry(main, "USER", "", Duration.ofDays(401), "words-old-question");
        UUID oldAnswer = entry(main, "HEPH", "", Duration.ofDays(401), "words-old-answer");
        UUID nearlyOld = entry(main, "USER", "", Duration.ofDays(387), "words-nearly-old");
        UUID recentQuestion = entry(main, "USER", "", Duration.ofDays(385), "words-recent-question");

        conversationRetention.sweepNow();

        assertThat(erased(oldQuestion)).isTrue();
        assertThat(erased(oldAnswer)).isTrue();
        assertThat(erased(nearlyOld))
                .as("the weekly run erases an entry in the two weeks before its limit")
                .isTrue();
        assertThat(erased(recentQuestion)).isFalse();
    }

    @Test
    void shouldEraseABacklogLargerThanOneBatch() {
        jdbc.update("""
                INSERT INTO mentor_entry (id, workspace_id, conversation_id, seq, kind, surface, text, payload,
                                          created_at)
                SELECT gen_random_uuid(), ?, ?, s, 'USER', 'APP', 'words-backlog',
                       jsonb_build_object('type', 'message', 'id', 'pi-' || s, 'message',
                           jsonb_build_object('role', 'user', 'content', jsonb_build_array(
                               jsonb_build_object('type', 'text', 'text', 'words-backlog')))),
                       now() - interval '500 days'
                FROM generate_series(?, ?) s
                """, workspace.getId(), main.getId(), seq + 1, seq + MentorRetentionSweeper.BATCH + 100);

        conversationRetention.sweepNow();

        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mentor_entry WHERE conversation_id = ? AND redacted_at IS NULL",
                        Long.class,
                        main.getId()))
                .isZero();
    }

    @Test
    void shouldEraseACopyingToolResultWithTheSlackRetentionAndKeepTheMessagesAndOtherResults() {
        connectSlack(workspace);
        UUID oldRead = entry(main, "TOOL_RESULT", "read", Duration.ofDays(17), "words-channel-message");
        UUID oldBash = entry(main, "TOOL_RESULT", "bash", Duration.ofDays(17), "words-grep-output");
        UUID recentRead = entry(main, "TOOL_RESULT", "read", Duration.ofDays(15), "words-recent-read");
        UUID oldSearch =
                entry(main, "TOOL_RESULT", "search_conversation", Duration.ofDays(17), "words-own-conversation");
        UUID oldAnswer = entry(main, "HEPH", "", Duration.ofDays(17), "words-answer");
        UUID summary = summary(main);
        UUID otherRead = entry(otherMain, "TOOL_RESULT", "read", Duration.ofDays(17), "words-other-workspace");

        slackRetention.sweepCopiesNow();

        assertThat(erased(oldRead)).isTrue();
        assertThat(erased(oldBash)).isTrue();
        assertThat(erased(recentRead)).isFalse();
        assertThat(erased(oldSearch))
                .as("a search result quotes the developer's own conversation and keeps its limit")
                .isFalse();
        assertThat(erased(oldAnswer)).isFalse();
        assertThat(erased(summary))
                .as("a summary over an erased copy goes with it")
                .isTrue();
        assertThat(erased(otherRead))
                .as("a workspace without Slack copies no channel message")
                .isFalse();
    }

    @Test
    void shouldEraseACopyingToolResultWhileTheSlackConnectionIsSuspended() {
        connectSlack(workspace, IntegrationState.SUSPENDED);
        UUID oldRead = entry(main, "TOOL_RESULT", "read", Duration.ofDays(17), "words-channel-message");

        slackRetention.sweepCopiesNow();

        assertThat(erased(oldRead)).isTrue();
    }

    @Test
    void shouldEraseEveryCopyingToolResultWhenTheSlackContentIsErased() {
        UUID read = entry(main, "TOOL_RESULT", "read", Duration.ofHours(1), "words-channel-message");
        UUID question = entry(main, "USER", "", Duration.ofHours(1), "words-app-question");

        slackContent.eraseWorkspace(workspace.getId());

        assertThat(erased(read)).isTrue();
        assertThat(erased(question)).isFalse();
    }
}
