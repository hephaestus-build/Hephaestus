package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedbackUnit;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionResultParser;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.ReviewRunNarrativeRow;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunNarrativeLookup;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Reads what a review run wrote about itself out of the job output it was composed into.
 *
 * <p>The next step is taken from the composed units rather than from the feedback ledger on purpose:
 * a unit the delivery gate suppressed is recorded with the whole rendered note as its body, so the
 * ledger keeps what the work would have been told and not the one sentence written about each
 * observation. The output is the only home for that sentence, which is why it is parsed here.
 */
@Component
@RequiredArgsConstructor
class ReviewRunNarrativeLookupAdapter implements ReviewRunNarrativeLookup {

    private final AgentJobRepository repository;
    private final FeedbackCompositionResultParser composition;

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ReviewRunNarrative> findByJobIds(long workspaceId, Collection<UUID> jobIds) {
        if (jobIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ReviewRunNarrative> narratives = new HashMap<>();
        for (ReviewRunNarrativeRow row : repository.findReviewRunNarratives(workspaceId, jobIds)) {
            narratives.put(row.getId(), toNarrative(row));
        }
        return Map.copyOf(narratives);
    }

    private ReviewRunNarrative toNarrative(ReviewRunNarrativeRow row) {
        JsonNode output = row.getOutput();
        return new ReviewRunNarrative(
                composition.lead(output),
                coverage(output, "evaluated"),
                coverage(output, "eligible"),
                durationSeconds(row.getStartedAt(), row.getCompletedAt()),
                nextStepsByObservation(output));
    }

    /**
     * The next step of every in-context unit this run composed, addressed to each observation it was
     * based on. Only the lane that speaks about the piece of work under review: an in-app unit is a
     * message about a habit across several pieces of work, so attaching its step to one observation
     * would answer "what should I do about this" with advice that is explicitly not about it.
     */
    private Map<UUID, String> nextStepsByObservation(@Nullable JsonNode output) {
        Map<UUID, String> nextSteps = new HashMap<>();
        for (ComposedFeedbackUnit unit : composition.parse(output, FeedbackChannel.IN_CONTEXT)) {
            String nextStep = unit.nextStep();
            if (nextStep == null || nextStep.isBlank()) {
                continue;
            }
            for (String observationId : unit.basedOn()) {
                try {
                    nextSteps.putIfAbsent(UUID.fromString(observationId), nextStep);
                } catch (IllegalArgumentException notAnObservationId) {
                    // The composer names admitted observation ids and the parser drops a unit that names
                    // anything else, so this is unreachable for output this build wrote.
                }
            }
        }
        return Map.copyOf(nextSteps);
    }

    private static @Nullable Integer coverage(@Nullable JsonNode output, String field) {
        if (output == null) {
            return null;
        }
        JsonNode count = output.path("practiceCoverage").path(field);
        return count.isIntegralNumber() && count.asInt() >= 0 ? count.asInt() : null;
    }

    /** Null while a run has not finished, and for a pair of timestamps that cannot both be true. */
    private static @Nullable Long durationSeconds(@Nullable Instant startedAt, @Nullable Instant completedAt) {
        if (startedAt == null || completedAt == null || completedAt.isBefore(startedAt)) {
            return null;
        }
        return Duration.between(startedAt, completedAt).toSeconds();
    }
}
