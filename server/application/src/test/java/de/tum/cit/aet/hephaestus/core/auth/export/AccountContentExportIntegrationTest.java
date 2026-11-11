package de.tum.cit.aet.hephaestus.core.auth.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountConversationExport;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountFeedbackExport;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecision;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecisionRepository;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionAction;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

class AccountContentExportIntegrationTest extends BaseIntegrationTest {
    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private ExportBundleAssembler assembler;

    @Autowired
    private ReviewIntegrationData data;

    @Autowired
    private FeedbackDecisionRepository decisions;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcClient jdbc;

    private long seq;

    @BeforeEach
    void clean() {
        databaseTestUtils.cleanDatabase();
    }

    @Test
    void shouldExportWhatTheAccountWroteAndTheFeedbackItReceivedButNotOtherPeoplesWords() {
        var scenario = data.scenario();
        long account = Objects.requireNonNull(scenario.account().getId());
        long workspace = scenario.workspace().getId();
        var other = data.member(workspace);
        UUID conversation = conversation(workspace, account);
        entry(conversation, "USER", "How do I split this pull request?", null);
        entry(conversation, "HEPH", "Split it by the two features.", null);
        entry(conversation, "TOOL_RESULT", "A tool read someone else's work.", null);
        entry(conversation, "USER", "A message that was redacted.", AT);
        entry(
                conversation(workspace, Objects.requireNonNull(other.getId())),
                "USER",
                "Another member's message.",
                null);

        var result = data.result(scenario, ResultOutcome.NOT_MET, AT);
        UUID delivered = feedback(result, account, "DELIVERED", "Name the tests after the behaviour.", AT);
        copy(delivered, workspace, 0, "POSTED", "Posted on the work.");
        copy(delivered, workspace, 1, "PLANNED", "Never posted.");
        UUID withdrawn = feedback(result, account, "DELIVERED", "Withdrawn words.", AT.plusSeconds(1));
        copy(withdrawn, workspace, 0, "POSTED", "Withdrawn piece.");
        decisions.saveAndFlush(FeedbackDecision.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspace)
                .feedbackId(withdrawn)
                .actorAccountId(account)
                .action(FeedbackDecisionAction.WITHDRAW)
                .reason("The wording was wrong.")
                .decidedAt(AT.plusSeconds(60))
                .build());
        feedback(result, account, "READY", "Not delivered yet.", null);
        feedback(result, account, "REPLACED", "Replaced words.", AT.plusSeconds(2));
        feedback(result, other.getId(), "DELIVERED", "Feedback for another member.", AT.plusSeconds(3));
        response(delivered, workspace, "DISAGREE", "The tests already say it.");

        ExportBundle bundle = assembler.assemble(account);

        assertThat(bundle.conversationMessages())
                .extracting(AccountConversationExport.Message::author, AccountConversationExport.Message::text)
                .containsExactly(
                        tuple("USER", "How do I split this pull request?"),
                        tuple("HEPH", "Split it by the two features."));
        assertThat(bundle.conversationMessages().getFirst().workspace())
                .isEqualTo(scenario.workspace().getWorkspaceSlug());
        assertThat(bundle.feedbackReceived())
                .extracting(AccountFeedbackExport.Received::feedbackId)
                .containsExactly(delivered, withdrawn);
        assertThat(bundle.feedbackReceived().getFirst()).satisfies(received -> {
            assertThat(received.body()).isEqualTo("Name the tests after the behaviour.");
            assertThat(received.pieces())
                    .extracting(AccountFeedbackExport.Piece::body)
                    .containsExactly("Posted on the work.");
            assertThat(received.withdrawnAt()).isNull();
        });
        assertThat(bundle.feedbackReceived().getLast()).satisfies(received -> {
            assertThat(received.body()).isNull();
            assertThat(received.pieces()).isEmpty();
            assertThat(received.withdrawnAt()).isEqualTo(AT.plusSeconds(60));
        });
        assertThat(bundle.feedbackResponses()).singleElement().satisfies(response -> {
            assertThat(response.feedbackId()).isEqualTo(delivered);
            assertThat(response.resolution()).isEqualTo("DISAGREE");
            assertThat(response.comment()).isEqualTo("The tests already say it.");
        });
        assertThat(mapper.writeValueAsString(bundle))
                .doesNotContain(
                        "someone else's work",
                        "redacted",
                        "Another member",
                        "Withdrawn",
                        "Never posted",
                        "Not delivered",
                        "Replaced words",
                        "another member",
                        "The wording was wrong");
    }

    private UUID conversation(long workspace, long account) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO mentor_conversation(id,workspace_id,account_id,created_at,last_activity_at)
            VALUES(:id,:ws,:account,:at,:at)
            """)
                .param("id", id)
                .param("ws", workspace)
                .param("account", account)
                .param("at", Timestamp.from(AT))
                .update();
        return id;
    }

    private long workspaceOf(UUID conversation) {
        return jdbc.sql("SELECT workspace_id FROM mentor_conversation WHERE id=:id")
                .param("id", conversation)
                .query(Long.class)
                .single();
    }

    private void entry(UUID conversation, String kind, String text, @Nullable Instant redactedAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO mentor_entry (id, workspace_id, conversation_id, seq, kind, surface, text, payload,
                                      slack_attempts, created_at, redacted_at)
            VALUES (:id, :ws, :conversation, :seq, :kind, 'APP', :text,
                    jsonb_build_object('type', 'message', 'id', 'pi-' || :id, 'message',
                        jsonb_build_object('role', CASE :kind WHEN 'USER' THEN 'user' WHEN 'HEPH' THEN 'assistant'
                            ELSE 'toolResult' END, 'toolName', 'read', 'toolCallId', 'call-' || :id,
                            'content', jsonb_build_array(jsonb_build_object('type', 'text', 'text', :text)))),
                    0, :at, :redacted)
            """)
                .param("id", id)
                .param("ws", workspaceOf(conversation))
                .param("conversation", conversation)
                .param("seq", ++seq)
                .param("kind", kind)
                .param("text", text)
                .param("at", Timestamp.from(AT.plusSeconds(seq)))
                .param("redacted", redactedAt == null ? null : Timestamp.from(redactedAt))
                .update();
    }

    private UUID feedback(Result result, long developer, String state, String body, @Nullable Instant deliveredAt) {
        var id = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO feedback(id,workspace_id,review_id,item_id,developer_id,destination,state,body,created_at,delivered_at)
            VALUES(:id,:ws,:review,:item,:developer,'ON_WORK',:state,:body,:at,:delivered)
            """)
                .param("id", id)
                .param("ws", result.getWorkspaceId())
                .param("review", result.getReviewId())
                .param("item", result.getItemId())
                .param("developer", developer)
                .param("state", state)
                .param("body", body)
                .param("at", Timestamp.from(AT))
                .param("delivered", deliveredAt == null ? null : Timestamp.from(deliveredAt))
                .update();
        return id;
    }

    private void copy(UUID feedback, long workspace, int position, String state, String body) {
        boolean posted = state.equals("POSTED");
        jdbc.sql("""
            INSERT INTO feedback_copy(id,workspace_id,feedback_id,kind,position,path,body,state,created_at,posted_at)
            VALUES(:id,:ws,:feedback,'FILE_NOTE',:position,'src/Example.java',:body,:state,:at,:posted)
            """)
                .param("id", UUID.randomUUID())
                .param("ws", workspace)
                .param("feedback", feedback)
                .param("position", position)
                .param("body", body)
                .param("state", state)
                .param("at", Timestamp.from(AT))
                .param("posted", posted ? Timestamp.from(AT.plusSeconds(10)) : null)
                .update();
    }

    private void response(UUID feedback, long workspace, String resolution, String comment) {
        jdbc.sql("""
            INSERT INTO feedback_response(id,workspace_id,feedback_id,resolution,comment,created_at)
            VALUES(:id,:ws,:feedback,:resolution,:comment,:at)
            """)
                .param("id", UUID.randomUUID())
                .param("ws", workspace)
                .param("feedback", feedback)
                .param("resolution", resolution)
                .param("comment", comment)
                .param("at", Timestamp.from(AT.plusSeconds(20)))
                .update();
    }
}
