package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedReview;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionResultParser;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.ReviewRunNarrativeRow;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunNarrativeLookup;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Reads what a review run wrote about itself out of the job output it was composed into.
 *
 * <p>The words are taken from the composition rather than from the feedback ledger: feedback the delivery gate
 * suppressed is recorded with the whole package as its body, so the ledger keeps what the work would have been
 * told and not what was written about each observation. The output is the only home for that.
 */
@Component
@RequiredArgsConstructor
class ReviewRunNarrativeLookupAdapter implements ReviewRunNarrativeLookup {

    private static final Logger log = LoggerFactory.getLogger(ReviewRunNarrativeLookupAdapter.class);

    private final AgentJobRepository repository;
    private final FeedbackCompositionResultParser composition;

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ReviewRunNarrative> findByJobIds(long workspaceId, Collection<UUID> jobIds) {
        if (jobIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ReviewRunNarrative> narratives = new HashMap<>();
        for (ReviewRunNarrativeRow row : repository.findReviewRunNarrativesByWorkspaceIdAndIdIn(workspaceId, jobIds)) {
            narratives.put(row.getId(), new ReviewRunNarrative(nextStepsByObservation(row.getId(), row.getOutput())));
        }
        return Map.copyOf(narratives);
    }

    /**
     * What the run wrote on the work about each observation by itself: the note it placed on a line that
     * observation cites, or, for a run composed before the review had one envelope, the next step it wrote about
     * it. A summary speaks about several observations at once, so it answers for none of them alone. Only the
     * surface about the piece of work under review counts: in-app feedback is about a way of working across
     * several pieces of work, so attaching it to one observation would answer "what should I do about this" with
     * advice that is explicitly not about it.
     */
    private Map<UUID, String> nextStepsByObservation(UUID jobId, @Nullable JsonNode output) {
        Map<UUID, String> nextSteps = new HashMap<>();
        ComposedReview review = composition.review(output);
        for (ComposedReview.InlineNote note : review == null ? List.<ComposedReview.InlineNote>of() : review.inline()) {
            UUID observationId = observationId(jobId, note.anchor().observationId());
            if (observationId != null) {
                nextSteps.putIfAbsent(observationId, note.body());
            }
        }
        composition.historicalNextSteps(output).forEach((id, nextStep) -> {
            UUID observationId = observationId(jobId, id);
            if (observationId != null) {
                nextSteps.putIfAbsent(observationId, nextStep);
            }
        });
        return nextSteps;
    }

    /**
     * The parser admits only ids the run's admitted observations carry, but it reads them back out of the
     * output the sandbox wrote as text; one that is not an id names no observation and cannot fail the page.
     */
    private static @Nullable UUID observationId(UUID jobId, String basedOn) {
        try {
            return UUID.fromString(basedOn);
        } catch (IllegalArgumentException notAnId) {
            log.warn("Composed feedback names an observation that is not an id: jobId={}", jobId);
            return null;
        }
    }
}
