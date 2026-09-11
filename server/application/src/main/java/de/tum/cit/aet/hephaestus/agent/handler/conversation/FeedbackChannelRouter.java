package de.tum.cit.aet.hephaestus.agent.handler.conversation;

import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomyPolicy;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaults;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.practices.review.autonomy.AutonomyResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Decides which of a cycle's observations are eligible for conversational delivery. An observation is
 * {@link ConversationRoutingDecision#ADMIT admitted} to the IN_CHAT channel iff ALL of: its provenance admits the
 * conversation channel, its practice autonomy admits the conversation channel, author-targeted, a
 * {@link Assessment#BAD} problem, has no natural inline anchor, and does not share a {@code recurrence_key} with a
 * DELIVERED IN_CONTEXT unit for the same recipient. Every other case is a named, testable non-admission reason.
 *
 * <p>Pure routing - it reads the feedback ledger but writes nothing. The {@link ConversationalFeedbackPreparer}
 * turns the admitted set into PREPARED units.
 */
@Component
public class FeedbackChannelRouter {

    private final FeedbackRepository feedbackRepository;
    private final ObservationRepository observationRepository;
    private final WorkspaceReviewDefaultsProvider workspaceDefaults;

    public FeedbackChannelRouter(
            FeedbackRepository feedbackRepository,
            ObservationRepository observationRepository,
            WorkspaceReviewDefaultsProvider workspaceDefaults) {
        this.feedbackRepository = feedbackRepository;
        this.observationRepository = observationRepository;
        this.workspaceDefaults = workspaceDefaults;
    }

    public List<Observation> admit(List<Observation> observations, long workspaceId, RoutingContext context) {
        WorkspaceReviewDefaults defaults = workspaceDefaults.forWorkspace(workspaceId);
        Map<UUID, PracticeAutonomy> autonomyByPracticeId =
                autonomyByPracticeId(observations, workspaceId, defaults.defaultAutonomy());
        List<Observation> admitted = new ArrayList<>();
        for (Observation observation : observations) {
            ConversationRoutingDecision decision =
                    route(observation, autonomyByPracticeId.get(observation.getId()), workspaceId, context);
            if (decision == ConversationRoutingDecision.ADMIT) {
                admitted.add(observation);
            }
        }
        return admitted;
    }

    public ConversationRoutingDecision route(
            Observation observation, @Nullable PracticeAutonomy autonomy, long workspaceId, RoutingContext context) {
        if (!PracticeAutonomyPolicy.delivers(observation.getOrigin(), autonomy, FeedbackChannel.IN_CHAT)) {
            return observation.getOrigin().delivers(FeedbackChannel.IN_CHAT)
                    ? ConversationRoutingDecision.PRACTICE_REQUIRES_APPROVAL
                    : ConversationRoutingDecision.BACKFILL_QUIET;
        }
        if (context.recipientRole() != RecipientRole.AUTHOR) {
            return ConversationRoutingDecision.REVIEWER_DEFERRED;
        }
        if (!(observation.getAssessmentStatus() == AssessmentStatus.ASSESSED)
                || observation.getOutcome() != Outcome.NEGATIVE) {
            return ConversationRoutingDecision.NOT_DELIVERABLE;
        }
        if (hasNaturalInlineAnchor(observation)) {
            return ConversationRoutingDecision.HAS_INLINE_ANCHOR;
        }
        String recurrenceKey = observation.getRecurrenceKey();
        if (recurrenceKey != null
                && feedbackRepository.existsDeliveredInContextForRecurrenceKey(
                        workspaceId, observation.getAboutUserId(), recurrenceKey)) {
            return ConversationRoutingDecision.ALREADY_DELIVERED_IN_CONTEXT;
        }
        return ConversationRoutingDecision.ADMIT;
    }

    private Map<UUID, PracticeAutonomy> autonomyByPracticeId(
            List<Observation> observations, long workspaceId, PracticeAutonomy workspaceDefault) {
        List<UUID> ids = observations.stream()
                .map(Observation::getId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, PracticeAutonomy> autonomyByPracticeId = new HashMap<>();
        for (var row : observationRepository.findPracticeAutonomyFor(ids, workspaceId)) {
            autonomyByPracticeId.put(
                    row.getObservationId(),
                    AutonomyResolver.resolvePractice(
                                    row.getPracticeAutonomy(), row.getGroupAutonomy(), workspaceDefault)
                            .autonomy());
        }
        return autonomyByPracticeId;
    }

    private static boolean hasNaturalInlineAnchor(Observation observation) {
        if (!ArtifactKinds.hasInlineLane(observation.getArtifactKind())) {
            return false;
        }
        JsonNode evidence = observation.getEvidence();
        if (evidence == null) {
            return false;
        }
        JsonNode citations = evidence.path("citations");
        if (!citations.isArray() || citations.isEmpty()) {
            return false;
        }
        for (JsonNode citation : citations) {
            if ("scm.pull-request.diff".equals(citation.path("sourceKind").asString())) {
                return true;
            }
        }
        return false;
    }
}
