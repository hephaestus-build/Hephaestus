package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.conversation.ConversationThreadProjection;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Unit tests for the conversation-thread content source. */
class ConversationThreadContentSourceTest extends BaseUnitTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Mock
    private ConversationThreadProjection projection;

    private ConversationThreadContentSource source;

    @BeforeEach
    void setUp() {
        source = new ConversationThreadContentSource(objectMapper, projection);
    }

    private AgentJob conversationJob() {
        var job = new AgentJob();
        var workspace = new Workspace();
        workspace.setId(7L);
        job.setWorkspace(workspace);
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("slack_channel_id", "C0ABC");
        metadata.put("slack_thread_ts", "1700000000.100000");
        job.setMetadata(metadata);
        return job;
    }

    @Test
    void supportsOnlyConversationReviewRequests() {
        assertThat(source.supports(new ContextRequest.ConversationReviewRequest(conversationJob())))
                .isTrue();
        assertThat(source.supports(new ContextRequest.MentorChatRequest(1L, 2L, UUID.randomUUID())))
                .isFalse();
    }

    @Test
    void writesConversationThreadJsonFromProjector() {
        AgentJob job = conversationJob();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("channel", "C0ABC");
        payload.put("messageCount", 3);
        when(projection.buildThreadPayload(7L, "C0ABC", "1700000000.100000")).thenReturn(payload);

        Map<String, byte[]> files = new HashMap<>();
        source.contribute(new ContextRequest.ConversationReviewRequest(job), files);

        // Required so a missing thread aborts rather than emitting a hollow positive — mirrors IssueContentSource.
        assertThat(source.required()).isTrue();
        assertThat(files).containsKey("inputs/context/conversation_thread.json");
        String written = new String(files.get("inputs/context/conversation_thread.json"));
        assertThat(written).contains("\"channel\" : \"C0ABC\"").contains("\"messageCount\" : 3");
    }

    @Test
    void reportsAnEmptyFullyEnumeratedThreadAsCompleteEmptyEvidence() {
        AgentJob job = conversationJob();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("messageCount", 0);
        payload.put("truncated", false);
        when(projection.buildThreadPayload(7L, "C0ABC", "1700000000.100000")).thenReturn(payload);
        SourceKind kind = new SourceKind("slack.conversation.thread");

        when(projection.threadReadability(7L, "C0ABC", "1700000000.100000"))
                .thenReturn(ConversationThreadProjection.ThreadReadability.READABLE);

        var contribution = source.capture(new ContextRequest.ConversationReviewRequest(job), Set.of(kind));

        assertThat(contribution.contentStates()).containsEntry(kind, SourceContentState.EMPTY);
        assertThat(contribution.completeness()).containsEntry(kind, SourceCompleteness.COMPLETE);
        assertThat(contribution.stateOverrides()).isEmpty();
    }

    @Test
    void reportsAWithheldChannelAsRedactedRatherThanAnEmptyConversation() {
        AgentJob job = conversationJob();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("messageCount", 0);
        payload.put("truncated", false);
        when(projection.buildThreadPayload(7L, "C0ABC", "1700000000.100000")).thenReturn(payload);
        when(projection.threadReadability(7L, "C0ABC", "1700000000.100000"))
                .thenReturn(ConversationThreadProjection.ThreadReadability.CONSENT_NOT_ACTIVE);
        SourceKind kind = new SourceKind("slack.conversation.thread");

        var contribution = source.capture(new ContextRequest.ConversationReviewRequest(job), Set.of(kind));

        // Reporting this as an empty conversation would have the model judge a developer's
        // communication on a thread it was never permitted to read.
        assertThat(contribution.stateOverrides())
                .containsEntry(kind, new SourceCaptureState.Redacted(SourceAbsenceReason.CONSENT_NOT_ACTIVE));
    }

    @Test
    void reportsAMissingThreadAsUnavailable() {
        AgentJob job = conversationJob();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("messageCount", 0);
        payload.put("truncated", false);
        when(projection.buildThreadPayload(7L, "C0ABC", "1700000000.100000")).thenReturn(payload);
        when(projection.threadReadability(7L, "C0ABC", "1700000000.100000"))
                .thenReturn(ConversationThreadProjection.ThreadReadability.NOT_FOUND);
        SourceKind kind = new SourceKind("slack.conversation.thread");

        var contribution = source.capture(new ContextRequest.ConversationReviewRequest(job), Set.of(kind));

        assertThat(contribution.stateOverrides())
                .containsEntry(kind, new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND));
    }
}
