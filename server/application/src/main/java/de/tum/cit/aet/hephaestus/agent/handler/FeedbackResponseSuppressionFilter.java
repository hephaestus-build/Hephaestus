package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.ReactionRepository;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.ReactionRepository.ObservationResolutionProjection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holds back feedback about an observation the developer has disputed or called not applicable, so a later review
 * of the same work does not say it again.
 *
 * <p>The developer answers feedback, and the feedback is bound to observations. Within one review, only the
 * observations the answered feedback was written from count: two observations there may share a place and still be
 * two behaviours. A later review records new observations, so there the answer carries to the same claim — the
 * same practice, piece of work, developer and place ({@code recurrence_key}), with the same presence and assessment.
 * When several answers speak for one observation, the newest wins, so withdrawing a dispute or marking the feedback
 * addressed lets the claim through again.
 */
@Component
class FeedbackResponseSuppressionFilter {

    private static final Logger log = LoggerFactory.getLogger(FeedbackResponseSuppressionFilter.class);

    private static final Set<FeedbackResolution> SUPPRESS_ACTIONS =
            Set.of(FeedbackResolution.DISPUTED, FeedbackResolution.NOT_APPLICABLE);

    private static final String SECRET_SCANNER = "secret-diff-scanner";

    private final ObservationRepository observationRepository;
    private final ReactionRepository reactionRepository;
    private final FeedbackLedgerRecorder feedbackLedgerRecorder;

    FeedbackResponseSuppressionFilter(
            ObservationRepository observationRepository,
            ReactionRepository reactionRepository,
            FeedbackLedgerRecorder feedbackLedgerRecorder) {
        this.observationRepository = observationRepository;
        this.reactionRepository = reactionRepository;
        this.feedbackLedgerRecorder = feedbackLedgerRecorder;
    }

    record SuppressionDecision(List<ValidatedObservation> deliverable, int suppressedCount) {}

    // Read-only tx: we run outside the handler's transaction and read scalar identity columns off the
    // persisted observations. recordSuppressed writes in its own REQUIRES_NEW tx, so readOnly does not bind it.
    @Transactional(readOnly = true)
    public SuppressionDecision evaluate(AgentJob job, List<ValidatedObservation> scopedObservations) {
        long workspaceId = job.getWorkspace().getId();
        List<Observation> persisted = observationRepository.findByAgentJobId(job.getId(), workspaceId);
        if (persisted.isEmpty()) {
            return new SuppressionDecision(scopedObservations, 0);
        }

        Map<String, Observation> persistedByOccurrence = new HashMap<>();
        for (Observation observation : persisted) {
            persistedByOccurrence.put(observation.getOccurrenceKey(), observation);
        }
        String[] recurrenceKeys = persisted.stream()
                .map(Observation::getRecurrenceKey)
                .filter(Objects::nonNull)
                .distinct()
                .toArray(String[]::new);
        List<ObservationResolutionProjection> answers = reactionRepository.findCurrentResolutions(
                workspaceId, persisted.stream().map(Observation::getId).toList(), recurrenceKeys);
        if (answers.isEmpty()) {
            return new SuppressionDecision(scopedObservations, 0);
        }

        List<ValidatedObservation> deliverable = new ArrayList<>(scopedObservations.size());
        int suppressed = 0;
        int suppressedIndex = 0;
        for (ValidatedObservation vf : scopedObservations) {
            Observation pf = persistedByOccurrence.get(vf.occurrenceKey());
            if (pf == null) {
                deliverable.add(vf);
                continue;
            }
            FeedbackResolution action = standingAnswer(pf, answers)
                    .map(answer -> FeedbackResolution.valueOf(answer.getResolution()))
                    .orElse(null);
            boolean unsuppressableSecret = vf.outcome() == Outcome.NEGATIVE
                    && vf.evidence() != null
                    && SECRET_SCANNER.equals(vf.evidence().path("detector").asString());
            if (!unsuppressableSecret && action != null && SUPPRESS_ACTIONS.contains(action)) {
                try {
                    feedbackLedgerRecorder.recordSuppressed(job, pf, reasonFor(action), suppressedIndex++);
                } catch (RuntimeException e) {
                    log.warn("Suppressed-ledger write failed (delivery unaffected): jobId={}", job.getId(), e);
                }
                suppressed++;
                continue;
            }
            deliverable.add(vf);
        }
        if (suppressed > 0) {
            log.info(
                    "Feedback-response filter: jobId={}, suppressed={}, delivered={}/{}",
                    job.getId(),
                    suppressed,
                    deliverable.size(),
                    scopedObservations.size());
        }
        return new SuppressionDecision(deliverable, suppressed);
    }

    /** The newest answer that speaks for {@code observation}: on its own feedback, or on the same claim earlier. */
    private static Optional<ObservationResolutionProjection> standingAnswer(
            Observation observation, List<ObservationResolutionProjection> answers) {
        return answers.stream()
                .filter(answer -> answer.getObservationId().equals(observation.getId())
                        || sameClaimInAnEarlierReview(observation, answer))
                .max(Comparator.comparing(ObservationResolutionProjection::getRespondedAt));
    }

    private static boolean sameClaimInAnEarlierReview(Observation observation, ObservationResolutionProjection answer) {
        UUID jobId = observation.getAgentJobId();
        return !answer.getAgentJobId().equals(jobId)
                && observation.getRecurrenceKey() != null
                && observation.getRecurrenceKey().equals(answer.getRecurrenceKey())
                && Objects.equals(nameOf(observation.getPresence()), answer.getPresence())
                && Objects.equals(nameOf(observation.getAssessment()), answer.getAssessment());
    }

    private static @Nullable String nameOf(@Nullable Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static FeedbackSuppressionReason reasonFor(FeedbackResolution action) {
        return action == FeedbackResolution.DISPUTED
                ? FeedbackSuppressionReason.REACTED_DISPUTED
                : FeedbackSuppressionReason.REACTED_NOT_APPLICABLE;
    }
}
