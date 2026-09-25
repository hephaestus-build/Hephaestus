package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a review run wrote about itself beside its observations: the next step it wrote for each piece of work
 * it measured. Kept here so the practices module reads it without knowing about agent-job persistence,
 * exactly as {@link ReviewOutcomeLookup} does for what a run decided.
 */
public interface ReviewRunNarrativeLookup {
    /** @return the narrative by review id; a run this workspace does not own is simply absent */
    Map<UUID, ReviewRunNarrative> findByJobIds(long workspaceId, Collection<UUID> jobIds);

    /**
     * @param nextStepByObservationId the next step the review wrote about each of its observations, whether or
     *     not the feedback carrying it was ever delivered. The developer's own page is the one surface silent
     *     mode does not gate, so a step written about their work reaches them there even when nothing was said
     *     on the work itself.
     */
    record ReviewRunNarrative(Map<UUID, String> nextStepByObservationId) {
        public ReviewRunNarrative {
            nextStepByObservationId = Map.copyOf(nextStepByObservationId);
        }

        public @Nullable String nextStepFor(UUID observationId) {
            return nextStepByObservationId.get(observationId);
        }
    }
}
