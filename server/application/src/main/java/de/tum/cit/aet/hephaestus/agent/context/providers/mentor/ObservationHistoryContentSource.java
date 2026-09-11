package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.agent.context.ContentSource;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest.MentorChatRequest;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.DeveloperTextSanitizer;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@RequiredArgsConstructor
public class ObservationHistoryContentSource implements ContentSource {

    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "observations_history.json";

    private static final int LOOKBACK_DAYS = 90;
    private static final int MAX_RECENT_OBSERVATIONS = 50;
    private static final int MAX_RECENT_REVIEWS = 20;

    private final UserRepository userRepository;
    private final ObservationRepository observationRepository;
    private final MentorContextQueryRepository queryRepository;
    private final ConversationConsentGate conversationConsentGate;
    private final ObservationVisibilityPolicy visibilityPolicy;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof MentorChatRequest;
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        MentorChatRequest req = (MentorChatRequest) request;
        ObjectNode payload = buildPayload(req.workspaceId(), req.developerId());
        try {
            files.put(OUTPUT_KEY, objectMapper.writeValueAsBytes(payload));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize observation history context", e);
        }
    }

    public ObjectNode buildPayload(Long workspaceId, Long developerId) {
        User user = userRepository
                .findById(developerId)
                .orElseThrow(() -> new EntityNotFoundException("User", developerId.toString()));
        Instant since = Instant.now().minus(LOOKBACK_DAYS, ChronoUnit.DAYS);

        List<Observation> recent = observationRepository.findRecentByDeveloperAndWorkspace(
                developerId,
                workspaceId,
                since,
                // Verdicts only: coaching on a run that declined to take a direction would invite the mentor to
                // invent one. The totals still reach it through the presence-count summary.
                true,
                PageRequest.of(0, MAX_RECENT_OBSERVATIONS));
        List<PullRequestReview> reviews = queryRepository.findReviewsReceivedSince(
                workspaceId, developerId, since, PageRequest.of(0, MAX_RECENT_REVIEWS));

        Set<UUID> authorizedIds =
                visibilityPolicy.permitsAll(workspaceId, recent, SourceUsePurpose.CONVERSATIONAL_MENTORING);
        List<Observation> authorized =
                recent.stream().filter(o -> authorizedIds.contains(o.getId())).toList();
        Set<Long> activeThreadIds =
                conversationConsentGate.activeThreadIds(workspaceId, conversationThreadIds(authorized));
        List<Observation> visible = authorized.stream()
                .filter(o -> !ArtifactKinds.CONVERSATION_THREAD.equals(o.getArtifactKind())
                        || isSurvivingConversation(o, activeThreadIds))
                .toList();
        boolean anyConversationSurvivor =
                visible.stream().anyMatch(o -> ArtifactKinds.CONVERSATION_THREAD.equals(o.getArtifactKind()));

        ObjectNode root = objectMapper.createObjectNode();
        // Untrusted-content quarantine: only when a Slack-derived (attacker-controllable) reasoning survives the gate
        // does this file carry the envelope — a PR/issue-only payload stays byte-identical (no _meta).
        if (anyConversationSurvivor) {
            conversationConsentGate.writeUntrustedEnvelope(root);
        }
        root.putObject("user").put("login", user.getLogin()).put("name", user.getName());

        ObjectNode summary = root.putObject("summary");
        summary.put("includedObservations", visible.size());

        ObjectNode presenceSummary = summary.putObject("byPresence");
        for (Presence v : Presence.values()) {
            presenceSummary.put(v.name(), 0L);
        }
        for (Observation observation : visible) {
            String presence = observation.getPresence() == null
                    ? observation.getAssessmentStatus().name()
                    : observation.getPresence().name();
            presenceSummary.put(presence, presenceSummary.path(presence).asLong() + 1);
        }

        ObjectNode severityNode = summary.putObject("bySeverity");
        for (Severity s : Severity.values()) {
            severityNode.put(s.name(), 0L);
        }
        for (Observation observation : visible) {
            if (observation.getSeverity() != null) {
                String severity = observation.getSeverity().name();
                severityNode.put(severity, severityNode.path(severity).asLong() + 1);
            }
        }

        ArrayNode observationsArr = root.putArray("recentObservations");
        for (Observation o : visible) {
            ObjectNode node = observationsArr.addObject();
            node.put("id", o.getId().toString());
            node.put("summary", o.getSummary());
            node.put("practiceSlug", o.getPractice().getSlug());
            node.put("assessmentStatus", o.getAssessmentStatus().name());
            node.put(
                    "presence", o.getPresence() == null ? null : o.getPresence().name());
            Assessment assessment = o.getAssessment();
            node.put("assessment", assessment == null ? null : assessment.name());
            Severity severity = o.getSeverity();
            node.put("severity", severity == null ? null : severity.name());
            node.put("observedAt", o.getObservedAt().toString());
            if (o.getArtifactKind() != null) {
                node.put("artifactKind", o.getArtifactKind().value());
            }
            if (o.getArtifactId() != null) {
                node.put("artifactId", o.getArtifactId());
            }
            if (o.getEvidence() != null && !o.getEvidence().isNull()) {
                node.set("evidence", o.getEvidence());
            }
            node.put("evidenceRationale", DeveloperTextSanitizer.sanitize(o.getEvidenceRationale()));
        }

        ArrayNode reviewsArr = root.putArray("reviewsReceived");
        for (PullRequestReview review : reviews) {
            ObjectNode node = reviewsArr.addObject();
            if (review.getPullRequest() != null) {
                node.put("prNumber", review.getPullRequest().getNumber());
                node.put("prTitle", review.getPullRequest().getTitle());
                node.put("url", review.getHtmlUrl());
            }
            if (review.getAuthor() != null) {
                node.put("reviewer", review.getAuthor().getLogin());
            }
            if (review.getState() != null) {
                node.put("state", review.getState().name());
            }
            node.put("hasComment", review.getBody() != null && !review.getBody().isBlank());
            node.put("submittedAt", review.getSubmittedAt().toString());
        }

        return root;
    }

    private static List<Long> conversationThreadIds(List<Observation> observations) {
        List<Long> ids = new ArrayList<>();
        for (Observation o : observations) {
            if (ArtifactKinds.CONVERSATION_THREAD.equals(o.getArtifactKind()) && o.getArtifactId() != null) {
                ids.add(o.getArtifactId());
            }
        }
        return ids;
    }

    private static boolean isSurvivingConversation(Observation o, Set<Long> activeThreadIds) {
        return (ArtifactKinds.CONVERSATION_THREAD.equals(o.getArtifactKind())
                && o.getArtifactId() != null
                && activeThreadIds.contains(o.getArtifactId()));
    }
}
