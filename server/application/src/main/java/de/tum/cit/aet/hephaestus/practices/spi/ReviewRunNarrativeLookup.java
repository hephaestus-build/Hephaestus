package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a review run wrote about itself beside its observations — how it opened, how much of the
 * catalogue it reached, how long it took, and the next step it wrote for each piece of work it
 * measured. Kept here so the practices module reads it without knowing about agent-job persistence,
 * exactly as {@link ReviewOutcomeLookup} does for what a run decided.
 */
public interface ReviewRunNarrativeLookup {
    /** @return the narrative by review id; a run this workspace does not own is simply absent */
    Map<UUID, ReviewRunNarrative> findByJobIds(long workspaceId, Collection<UUID> jobIds);

    /**
     * @param lead the one sentence the review opened with, about the whole piece of work
     * @param practicesEvaluated how many of the eligible practices this run actually reached
     * @param practicesEligible how many practices this run was eligible to review
     * @param durationSeconds how long the run took, from start to completion
     * @param nextStepByObservationId the next step the review wrote about each observation, whether or
     *     not the feedback carrying it was ever delivered. The developer's own page is the one surface
     *     silent mode does not gate, so a step written about their work reaches them there even when
     *     nothing was said on the work itself.
     */
    record ReviewRunNarrative(
            @Nullable String lead,
            @Nullable Integer practicesEvaluated,
            @Nullable Integer practicesEligible,
            @Nullable Long durationSeconds,
            Map<UUID, String> nextStepByObservationId) {
        public ReviewRunNarrative {
            nextStepByObservationId = Map.copyOf(nextStepByObservationId);
        }

        public @Nullable String nextStepFor(UUID observationId) {
            return nextStepByObservationId.get(observationId);
        }
    }
}
