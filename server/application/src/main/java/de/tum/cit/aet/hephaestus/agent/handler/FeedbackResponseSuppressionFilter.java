package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.ReactionRepository;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Suppresses repeat delivery when the developer has already rejected the same feedback locus. */
@Component
class FeedbackResponseSuppressionFilter {

    private static final Logger log = LoggerFactory.getLogger(FeedbackResponseSuppressionFilter.class);

    private static final Set<FeedbackResolution> SUPPRESS_ACTIONS =
            Set.of(FeedbackResolution.DISPUTED, FeedbackResolution.NOT_APPLICABLE);

    private static final String SECRET_SCANNER = "secret-diff-scanner";

    private final ObservationRepository observationRepository;
    private final ReactionRepository reactionRepository;
    private final FeedbackLedgerRecorder feedbackLedgerRecorder;
    private final PracticeReviewProperties reviewProperties;

    FeedbackResponseSuppressionFilter(
            ObservationRepository observationRepository,
            ReactionRepository reactionRepository,
            FeedbackLedgerRecorder feedbackLedgerRecorder,
            PracticeReviewProperties reviewProperties) {
        this.observationRepository = observationRepository;
        this.reactionRepository = reactionRepository;
        this.feedbackLedgerRecorder = feedbackLedgerRecorder;
        this.reviewProperties = reviewProperties;
    }

    record SuppressionDecision(List<ValidatedObservation> deliverable, int suppressedCount) {}

    // Read-only tx: we run outside the handler's transaction and read scalar identity columns off the
    // persisted observations. recordSuppressed writes in its own REQUIRES_NEW tx, so readOnly does not bind it.
    @Transactional(readOnly = true)
    public SuppressionDecision evaluate(AgentJob job, List<ValidatedObservation> scopedObservations) {
        if (!reviewProperties.reactionSuppression()) {
            return new SuppressionDecision(scopedObservations, 0);
        }
        List<Observation> persisted = observationRepository.findByAgentJobId(
                job.getId(), job.getWorkspace().getId());
        if (persisted.isEmpty()) {
            return new SuppressionDecision(scopedObservations, 0);
        }

        Observation any = persisted.get(0);
        long aboutUserId = any.getAboutUserId();

        // Keep occurrences distinct when several observations share a recurrence locus.
        Map<String, Observation> persistedByOccurrence = new HashMap<>();
        Set<String> recurrenceKeys = new HashSet<>();
        for (Observation f : persisted) {
            persistedByOccurrence.put(f.getOccurrenceKey(), f);
            if (f.getRecurrenceKey() != null) {
                recurrenceKeys.add(f.getRecurrenceKey());
            }
        }
        if (recurrenceKeys.isEmpty()) {
            return new SuppressionDecision(scopedObservations, 0);
        }
        Map<String, FeedbackResolution> actionByKey = new HashMap<>();
        for (var row : reactionRepository.findCurrentResolutionByRecurrenceKeys(
                recurrenceKeys, aboutUserId, job.getWorkspace().getId())) {
            actionByKey.put(row.getRecurrenceKey(), FeedbackResolution.valueOf(row.getResolution()));
        }
        if (actionByKey.isEmpty()) {
            return new SuppressionDecision(scopedObservations, 0);
        }

        List<ValidatedObservation> deliverable = new ArrayList<>(scopedObservations.size());
        int suppressed = 0;
        int suppressedIndex = 0;
        for (ValidatedObservation vf : scopedObservations) {
            String key = vf.recurrenceKey();
            if (key == null) {
                deliverable.add(vf);
                continue;
            }
            FeedbackResolution action = actionByKey.get(key);
            boolean unsuppressableSecret = vf.assessment() == Assessment.BAD
                    && vf.evidence() != null
                    && SECRET_SCANNER.equals(vf.evidence().path("detector").asString());
            if (!unsuppressableSecret && action != null && SUPPRESS_ACTIONS.contains(action)) {
                Observation pf = persistedByOccurrence.get(vf.occurrenceKey());
                if (pf != null) {
                    try {
                        feedbackLedgerRecorder.recordSuppressed(job, pf, reasonFor(action), suppressedIndex++);
                    } catch (RuntimeException e) {
                        log.warn("Suppressed-ledger write failed (delivery unaffected): jobId={}", job.getId(), e);
                    }
                }
                suppressed++;
                continue;
            }
            if (action == FeedbackResolution.ADDRESSED && vf.assessment() == Assessment.BAD) {
                deliverable.add(withEscalatedReasoning(vf));
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

    private static ValidatedObservation withEscalatedReasoning(ValidatedObservation vf) {
        String prefix = "You previously marked this as fixed, but it is still present. ";
        String reasoning =
                vf.evidenceRationale() == null || vf.evidenceRationale().isBlank()
                        ? prefix.trim()
                        : prefix + vf.evidenceRationale();
        return new ValidatedObservation(
                vf.practiceSlug(),
                vf.summary(),
                vf.assessmentStatus(),
                vf.presence(),
                vf.assessment(),
                vf.severity(),
                vf.evidence(),
                reasoning);
    }

    private static FeedbackSuppressionReason reasonFor(FeedbackResolution action) {
        return action == FeedbackResolution.DISPUTED
                ? FeedbackSuppressionReason.REACTED_DISPUTED
                : FeedbackSuppressionReason.REACTED_NOT_APPLICABLE;
    }
}
