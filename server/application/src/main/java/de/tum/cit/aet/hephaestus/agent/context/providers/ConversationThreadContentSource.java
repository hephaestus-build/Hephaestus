package de.tum.cit.aet.hephaestus.agent.context.providers;

import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireText;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceContribution;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceSource;
import de.tum.cit.aet.hephaestus.agent.conversation.ChatSignals;
import de.tum.cit.aet.hephaestus.agent.conversation.ConversationThreadProjection;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewContextBuilder;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Materialises the chat.conversation_thread detection context under {@code inputs/context/} — the repo-less,
 * no-diff counterpart of {@link IssueContentSource} for a settled Slack thread:
 *
 * <ul>
 *   <li>{@code conversation_thread.json} — the ordered, non-tombstoned human turns of one thread, each with its
 *       author and text, wrapped in the untrusted-content quarantine envelope.</li>
 * </ul>
 *
 * <p>Reads the Slack substrate through the agent-owned {@link ConversationThreadProjection} SPI, implemented by
 * {@code integration.slack} (the owner of the Slack schema) — this content source never touches {@code slack_*}
 * tables itself, so the coupling runs one way ({@code integration.slack → agent}). Required: a job whose metadata
 * does not name a thread is a preparation failure.
 */
@Component
public class ConversationThreadContentSource implements EvidenceSource, ReviewContextBuilder {

    /** Declares that this artifact kind is reviewable, gating whether practices may target it. */
    @Override
    public ArtifactKind artifactKind() {
        return ChatSignals.CONVERSATION_THREAD;
    }

    private static final SourceKind KIND = new SourceKind("slack.conversation.thread");

    @Override
    public Set<SourceKind> sourceKinds() {
        return Set.of(KIND);
    }

    @Override
    public SourceKind sourceKindFor(String path) {
        return KIND;
    }

    private static final Logger log = LoggerFactory.getLogger(ConversationThreadContentSource.class);

    static final String OUTPUT_KEY = OUTPUT_PREFIX + "conversation_thread.json";

    private final ObjectMapper objectMapper;
    private final ConversationThreadProjection projection;

    public ConversationThreadContentSource(ObjectMapper objectMapper, ConversationThreadProjection projection) {
        this.objectMapper = objectMapper;
        this.projection = projection;
    }

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof ContextRequest.ConversationReviewRequest;
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        AgentJob job = ((ContextRequest.ConversationReviewRequest) request).job();
        JsonNode metadata = Objects.requireNonNull(job.getMetadata(), "conversation job metadata is required");
        if (metadata == null || metadata.isNull() || metadata.isMissingNode()) {
            throw new JobPreparationException("Job has no metadata: jobId=" + job.getId());
        }
        if (job.getWorkspace() == null) {
            throw new JobPreparationException("Job has no workspace: jobId=" + job.getId());
        }
        long workspaceId = job.getWorkspace().getId();
        String channelId = requireText(metadata, "slack_channel_id");
        String threadTs = requireText(metadata, "slack_thread_ts");

        ObjectNode payload = projection.buildThreadPayload(workspaceId, channelId, threadTs);
        try {
            files.put(OUTPUT_KEY, objectMapper.writeValueAsBytes(payload));
        } catch (Exception e) {
            throw new JobPreparationException("Failed to serialize conversation_thread.json: " + e.getMessage(), e);
        }
        log.info(
                "Conversation context built: channel={}, threadTs={}, turns={}, jobId={}",
                channelId,
                threadTs,
                payload.path("messageCount").asInt(),
                job.getId());
    }

    @Override
    public EvidenceContribution capture(ContextRequest request, Set<SourceKind> selectedKinds) {
        EvidenceContribution captured = EvidenceSource.super.capture(request, selectedKinds);
        if (!selectedKinds.contains(KIND)) return captured;
        AgentJob job = ((ContextRequest.ConversationReviewRequest) request).job();
        JsonNode metadata = Objects.requireNonNull(job.getMetadata(), "Conversation jobs require metadata");
        JsonNode payload;
        try {
            payload = objectMapper.readTree(captured.files().get(OUTPUT_KEY));
        } catch (Exception e) {
            throw new IllegalStateException("Serialized conversation thread could not be read", e);
        }
        int messageCount = payload.path("messageCount").asInt();
        String slackLastTs = metadata.path("slack_last_ts").asString(null);
        Instant effectiveTime = projection.sourceEffectiveAt(slackLastTs);
        Map<SourceKind, Instant> effectiveAt = effectiveTime == null ? Map.of() : Map.of(KIND, effectiveTime);
        // An empty payload isn't necessarily a thread with no messages — a paused/withdrawn consent or a
        // deleted thread must not be misreported as a conversation the developer did not have.
        Map<SourceKind, SourceCaptureState> stateOverrides = messageCount == 0
                ? absenceOf(projection.threadReadability(
                        job.getWorkspace().getId(),
                        metadata.path("slack_channel_id").asString(),
                        metadata.path("slack_thread_ts").asString()))
                : Map.of();
        return new EvidenceContribution(
                captured.files(),
                Map.of(
                        KIND,
                        payload.path("truncated").asBoolean()
                                ? SourceCompleteness.PARTIAL
                                : SourceCompleteness.COMPLETE),
                Map.of(),
                Map.of(),
                effectiveAt,
                Map.of(KIND, messageCount == 0 ? SourceContentState.EMPTY : SourceContentState.NON_EMPTY),
                stateOverrides);
    }

    private static Map<SourceKind, SourceCaptureState> absenceOf(
            ConversationThreadProjection.ThreadReadability readability) {
        return switch (readability) {
            case READABLE -> Map.of();
            case CONSENT_NOT_ACTIVE ->
                Map.of(KIND, new SourceCaptureState.Redacted(SourceAbsenceReason.CONSENT_NOT_ACTIVE));
            case NOT_FOUND -> Map.of(KIND, new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND));
        };
    }
}
