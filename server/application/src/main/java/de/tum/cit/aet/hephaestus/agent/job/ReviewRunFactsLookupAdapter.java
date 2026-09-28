package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeCoverageLedger;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionResultParser;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.ReviewRunFactsRow;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunFactsLookup;
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

/** Reads what a review run recorded about itself out of the job row and the output it wrote. */
@Component
@RequiredArgsConstructor
class ReviewRunFactsLookupAdapter implements ReviewRunFactsLookup {

    private final AgentJobRepository repository;
    private final FeedbackCompositionResultParser composition;

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ReviewRunFacts> findByJobIds(long workspaceId, Collection<UUID> jobIds) {
        if (jobIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ReviewRunFacts> facts = new HashMap<>();
        for (ReviewRunFactsRow row : repository.findReviewRunFacts(workspaceId, jobIds)) {
            PracticeCoverageLedger coverage = PracticeCoverageLedger.from(row.getOutput());
            facts.put(
                    row.getId(),
                    new ReviewRunFacts(
                            AgentJobReviewRunStates.of(row.getStatus()),
                            row.getTriggerMode(),
                            composition.lead(row.getOutput()),
                            coverage.evaluated(),
                            coverage.eligible(),
                            seconds(row.getStartedAt(), row.getCompletedAt()),
                            row.getStartedAt(),
                            row.getCompletedAt(),
                            row.getDeliveryCommentId()));
        }
        return Map.copyOf(facts);
    }

    /** A run still going, or one whose edges were never both recorded, has no duration to report. */
    private static @Nullable Long seconds(@Nullable Instant startedAt, @Nullable Instant completedAt) {
        if (startedAt == null || completedAt == null || completedAt.isBefore(startedAt)) {
            return null;
        }
        return Duration.between(startedAt, completedAt).toSeconds();
    }
}
