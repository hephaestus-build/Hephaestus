package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.agent.context.ContentSource;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest.MentorChatRequest;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeFeedbackDeliveryPolicy;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.feedback.ConversationBriefBody;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyStage;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicySurface;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository.PreparedConversationFact;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class PreparedConversationFeedbackContentSource implements ContentSource {

    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "prepared_conversation_feedback.json";

    private static final int MAX_PREPARED = 3;
    private static final int MAX_CANDIDATES = 12;

    private final FeedbackObservationRepository feedbackObservationRepository;
    private final ConversationConsentGate consentGate;
    private final ObjectMapper objectMapper;
    private final ObservationRepository observationRepository;
    private final ObservationVisibilityPolicy visibilityPolicy;
    private final AgentJobRepository agentJobRepository;
    private final PracticeFeedbackDeliveryPolicy deliveryPolicy;
    private final FeedbackRepository feedbackRepository;

    public PreparedConversationFeedbackContentSource(
            FeedbackObservationRepository feedbackObservationRepository,
            ConversationConsentGate consentGate,
            ObjectMapper objectMapper,
            ObservationRepository observationRepository,
            ObservationVisibilityPolicy visibilityPolicy,
            AgentJobRepository agentJobRepository,
            PracticeFeedbackDeliveryPolicy deliveryPolicy,
            FeedbackRepository feedbackRepository) {
        this.feedbackObservationRepository = feedbackObservationRepository;
        this.consentGate = consentGate;
        this.objectMapper = objectMapper;
        this.observationRepository = observationRepository;
        this.visibilityPolicy = visibilityPolicy;
        this.agentJobRepository = agentJobRepository;
        this.deliveryPolicy = deliveryPolicy;
        this.feedbackRepository = feedbackRepository;
    }

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof MentorChatRequest;
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    @Transactional
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        MentorChatRequest req = (MentorChatRequest) request;
        long workspaceId = req.workspaceId();
        List<PreparedConversationFact> prepared =
                feedbackObservationRepository.findPreparedConversationFactsForRecipient(
                        workspaceId, req.developerId(), PageRequest.of(0, MAX_CANDIDATES));

        Set<Long> activeThreadIds = consentGate.activeThreadIds(workspaceId, conversationThreadIds(prepared));
        Map<UUID, Observation> observations = observationsById(workspaceId, prepared);
        Set<UUID> visible = visibilityPolicy.permitsForNewDelivery(
                workspaceId, observations.values(), SourceUsePurpose.CONVERSATIONAL_MENTORING);

        ObjectNode root = objectMapper.createObjectNode();

        consentGate.writeUntrustedEnvelope(root);

        ArrayNode arr = root.putArray("preparedConversationFeedback");
        for (PreparedConversationFact fact : prepared) {
            if (arr.size() >= MAX_PREPARED) break;
            if (!visible.contains(fact.getObservationId())) {
                continue;
            }
            if (ArtifactKinds.CONVERSATION_THREAD.equals(fact.getArtifactKind())
                    && (fact.getArtifactId() == null || !activeThreadIds.contains(fact.getArtifactId()))) {
                continue;
            }
            AgentJob job = agentJobRepository
                    .findByIdAndWorkspaceId(fact.getAgentJobId(), workspaceId)
                    .orElse(null);
            if (job == null) {
                feedbackRepository.markPreparedSuppressed(
                        fact.getFeedbackId(), workspaceId, FeedbackSuppressionReason.ARTIFACT_GONE.name());
                continue;
            }
            var decision = deliveryPolicy.evaluateForRecipient(
                    job,
                    DeliveryPolicyStage.EGRESS,
                    fact.getFeedbackId(),
                    DeliveryPolicySurface.CONVERSATION,
                    req.developerId(),
                    Set.of(fact.getPracticeSlug()));
            if (!decision.allowed()) {
                feedbackRepository.markPreparedSuppressed(
                        fact.getFeedbackId(), workspaceId, decision.refusal().name());
                continue;
            }
            ObjectNode node = arr.addObject();
            node.put("observationId", fact.getObservationId().toString());
            node.put("practiceSlug", fact.getPracticeSlug());
            node.put("practiceName", fact.getPracticeName());
            node.put("summary", fact.getSummary());
            if (fact.getSeverity() != null) {
                node.put("severity", fact.getSeverity().name());
            }
            if (fact.getEvidenceRationale() != null) {
                node.put("evidenceRationale", fact.getEvidenceRationale());
            }
            if (fact.getArtifactKind() != null) {
                node.put("artifactKind", fact.getArtifactKind().value());
            }
            if (fact.getArtifactId() != null) {
                node.put("artifactId", fact.getArtifactId());
            }
            if (fact.getPreparedAt() != null) {
                node.put("preparedAt", fact.getPreparedAt().toString());
            }
            Observation observation = observations.get(fact.getObservationId());
            if (observation != null && observation.getEvidence() != null) {
                node.set("evidence", observation.getEvidence());
            }
            writeNotes(node, fact.getBody());
        }
        root.put("totalPrepared", arr.size());
        try {
            files.put(OUTPUT_KEY, objectMapper.writeValueAsBytes(root));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize prepared conversation feedback context", e);
        }
    }

    private static void writeNotes(ObjectNode node, String body) {
        ConversationBriefBody.Brief brief = ConversationBriefBody.parse(body);
        if (brief == null) {
            return;
        }
        node.put("topic", brief.title());
        ObjectNode notes = node.putObject("notes");
        notes.put("situation", brief.situation());
        notes.put("capability", brief.capability());
        notes.put("evidenceSummary", brief.evidenceSummary());
        notes.put("inConversationSignal", brief.inConversationSignal());
        if (brief.alreadySaid() != null) {
            notes.put("alreadySaid", brief.alreadySaid());
        }
    }

    private Map<UUID, Observation> observationsById(long workspaceId, List<PreparedConversationFact> facts) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (PreparedConversationFact fact : facts) {
            ids.add(fact.getObservationId());
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Observation> rows = observationRepository.findAllByIdInAndWorkspaceId(ids, workspaceId);
        Map<UUID, Observation> byId = new HashMap<>(rows.size());
        for (Observation observation : rows) {
            byId.put(observation.getId(), observation);
        }
        return byId;
    }

    private static List<Long> conversationThreadIds(List<PreparedConversationFact> facts) {
        List<Long> ids = new ArrayList<>();
        for (PreparedConversationFact fact : facts) {
            if (ArtifactKinds.CONVERSATION_THREAD.equals(fact.getArtifactKind()) && fact.getArtifactId() != null) {
                ids.add(fact.getArtifactId());
            }
        }
        return ids;
    }
}
