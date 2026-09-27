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
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.LatestRun;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Materialises {@code inputs/context/observations_history.json}: a bounded recent sample of what reviews recorded
 * about the developer, never their whole history. {@code recentObservations} and {@code abstentions} are the verdicts
 * and the {@code NOT_APPLICABLE}/{@code UNDETERMINED} results of each claim's latest run; {@code earlierObservations}
 * are earlier runs of a claim whose latest run is listed. Every row passes the same visibility and consent checks,
 * and an earlier run is listed only beside a latest run this conversation may see, so none hints at one it may not.
 */
@Component
@RequiredArgsConstructor
public class ObservationHistoryContentSource implements ContentSource {

    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "observations_history.json";

    private static final int LOOKBACK_DAYS = 90;
    private static final int MAX_RECENT_OBSERVATIONS = 50;
    private static final int MAX_ABSTENTIONS = 20;
    private static final int MAX_EARLIER_OBSERVATIONS = 20;
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
        Instant preparedAt = Instant.now();
        Instant since = preparedAt.minus(LOOKBACK_DAYS, ChronoUnit.DAYS);

        List<Observation> recent = new ArrayList<>(usable(
                workspaceId,
                observationRepository.findRecentByDeveloperAndWorkspace(
                        developerId,
                        workspaceId,
                        since,
                        List.of(AssessmentStatus.ASSESSED.name()),
                        PageRequest.of(0, MAX_RECENT_OBSERVATIONS))));
        List<Observation> abstentions = new ArrayList<>(usable(
                workspaceId,
                observationRepository.findRecentByDeveloperAndWorkspace(
                        developerId,
                        workspaceId,
                        since,
                        List.of(AssessmentStatus.NOT_APPLICABLE.name(), AssessmentStatus.UNDETERMINED.name()),
                        PageRequest.of(0, MAX_ABSTENTIONS))));
        List<Observation> latest = new ArrayList<>(recent);
        latest.addAll(abstentions);
        List<Observation> earlier = new ArrayList<>(usable(
                        workspaceId,
                        observationRepository.findEarlierRunsByDeveloperAndWorkspace(
                                developerId, workspaceId, since, PageRequest.of(0, MAX_EARLIER_OBSERVATIONS)))
                .stream()
                .filter(candidate -> outrankedByListedRun(latest, candidate))
                .toList());
        List<PullRequestReview> reviews = queryRepository.findReviewsReceivedSince(
                workspaceId, developerId, since, PageRequest.of(0, MAX_RECENT_REVIEWS));

        // Oldest rows go first, earlier runs before latest ones, so the file reaches the model whole.
        ObjectNode root = render(user, preparedAt, recent, abstentions, earlier, reviews);
        while (objectMapper.writeValueAsString(root).length() > MentorContextKeys.FETCH_CONTEXT_MAX_CHARS) {
            List<Observation> shrink = !earlier.isEmpty() ? earlier : !abstentions.isEmpty() ? abstentions : recent;
            if (shrink.isEmpty()) {
                break;
            }
            shrink.removeLast();
            root = render(user, preparedAt, recent, abstentions, earlier, reviews);
        }
        return root;
    }

    /** Whether a listed latest run of the same claim is newer, by the one rule that picks a claim's latest run. */
    private static boolean outrankedByListedRun(List<Observation> latest, Observation candidate) {
        List<Observation> claim = new ArrayList<>(latest);
        claim.add(candidate);
        return LatestRun.perClaim(claim).stream().noneMatch(o -> o.getId().equals(candidate.getId()));
    }

    /** The rows this conversation may use: evidence authorized for it, and a conversation still consented to. */
    private List<Observation> usable(Long workspaceId, List<Observation> rows) {
        Set<UUID> authorizedIds =
                visibilityPolicy.permitsAll(workspaceId, rows, SourceUsePurpose.CONVERSATIONAL_MENTORING);
        List<Observation> authorized =
                rows.stream().filter(o -> authorizedIds.contains(o.getId())).toList();
        Set<Long> activeThreadIds =
                conversationConsentGate.activeThreadIds(workspaceId, conversationThreadIds(authorized));
        return authorized.stream()
                .filter(o -> !ArtifactKinds.CONVERSATION_THREAD.equals(o.getArtifactKind())
                        || isSurvivingConversation(o, activeThreadIds))
                .toList();
    }

    private ObjectNode render(
            User user,
            Instant preparedAt,
            List<Observation> recent,
            List<Observation> abstentions,
            List<Observation> earlier,
            List<PullRequestReview> reviews) {
        ObjectNode root = objectMapper.createObjectNode();
        // Untrusted-content quarantine: only when a Slack-derived (attacker-controllable) reasoning survives the gate
        // does this file carry the envelope — a PR/issue-only payload stays byte-identical (no _meta).
        if (Stream.of(recent, abstentions, earlier)
                .flatMap(List::stream)
                .anyMatch(o -> ArtifactKinds.CONVERSATION_THREAD.equals(o.getArtifactKind()))) {
            conversationConsentGate.writeUntrustedEnvelope(root);
        }
        root.putObject("user").put("login", user.getLogin()).put("name", user.getName());
        root.set("coverage", objectMapper.valueToTree(Coverage.of(preparedAt)));

        ObjectNode summary = root.putObject("summary");
        summary.put("includedObservations", recent.size());

        ObjectNode presenceSummary = summary.putObject("byPresence");
        for (Presence v : Presence.values()) {
            presenceSummary.put(v.name(), 0L);
        }
        for (Observation observation : recent) {
            String presence = observation.getPresence() == null
                    ? observation.getAssessmentStatus().name()
                    : observation.getPresence().name();
            presenceSummary.put(presence, presenceSummary.path(presence).asLong() + 1);
        }

        ObjectNode severityNode = summary.putObject("bySeverity");
        for (Severity s : Severity.values()) {
            severityNode.put(s.name(), 0L);
        }
        for (Observation observation : recent) {
            if (observation.getSeverity() != null) {
                String severity = observation.getSeverity().name();
                severityNode.put(severity, severityNode.path(severity).asLong() + 1);
            }
        }

        describe(root.putArray("recentObservations"), recent);
        describe(root.putArray("abstentions"), abstentions);
        describe(root.putArray("earlierObservations"), earlier);

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

    private static void describe(ArrayNode into, List<Observation> observations) {
        for (Observation o : observations) {
            ObjectNode node = into.addObject();
            node.put("id", o.getId().toString());
            node.put("reviewId", o.getAgentJobId().toString());
            node.put("origin", o.getOrigin().name());
            node.put("summary", o.getSummary());
            node.put("practiceSlug", o.getPractice().getSlug());
            node.put("assessmentStatus", o.getAssessmentStatus().name());
            node.put("outcome", o.getOutcome() == null ? null : o.getOutcome().name());
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
    }

    /**
     * The bounds of the three observation lists. Apart from {@code preparedAt} it is constant, so it reveals nothing
     * about rows outside the scope, including whether any exist.
     */
    record Coverage(
            Scope scope, Instant preparedAt, int lookbackDays, MaxEntries maxEntries, List<OutsideScope> outsideScope) {

        static Coverage of(Instant preparedAt) {
            return new Coverage(
                    Scope.CONVERSATION_AUTHORIZED_OBSERVATIONS_ABOUT_DEVELOPER,
                    preparedAt,
                    LOOKBACK_DAYS,
                    new MaxEntries(
                            MAX_RECENT_OBSERVATIONS, MAX_ABSTENTIONS, MAX_EARLIER_OBSERVATIONS, MAX_RECENT_REVIEWS),
                    List.of(OutsideScope.values()));
        }

        record MaxEntries(int recentObservations, int abstentions, int earlierObservations, int reviewsReceived) {}

        enum Scope {
            CONVERSATION_AUTHORIZED_OBSERVATIONS_ABOUT_DEVELOPER,
        }

        enum OutsideScope {
            INVALIDATED,
            MEASURED_UNDER_CHANGED_REVIEW_RULES,
            EVIDENCE_NOT_USABLE_IN_CONVERSATION,
            CONVERSATION_CONSENT_NOT_ACTIVE,
            HIDDEN_REPOSITORIES,
        }
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
