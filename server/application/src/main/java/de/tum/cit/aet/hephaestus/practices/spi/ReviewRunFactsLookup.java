package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOutcomeLookup.ReviewRunState;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a review run records about the run itself: how it ended, what occasioned it, how long it took, the
 * sentence it opened its feedback with, how much of the practice set it reached and where its summary
 * landed. Kept here so the
 * practices module reads it without knowing about agent-job persistence, exactly as
 * {@link ReviewRunNarrativeLookup} does for what a run wrote about each observation.
 */
public interface ReviewRunFactsLookup {
    /** @return the facts by review id; a run this workspace does not own is simply absent */
    Map<UUID, ReviewRunFacts> findByJobIds(long workspaceId, Collection<UUID> jobIds);

    /**
     * @param state how the run ended, in the same coarse vocabulary {@link ReviewOutcomeLookup} uses
     * @param lead the sentence the run opened its composed feedback with, or null when it wrote none
     * @param practicesEvaluated how many practices the run measured, from the coverage ledger it wrote
     * @param practicesEligible how many it was eligible for; null together with {@code practicesEvaluated}
     *     when the run wrote no ledger a reader can trust
     * @param durationSeconds wall-clock seconds from start to finish, or null while either edge is unrecorded
     * @param startedAt when the sandbox began, or null for a run still waiting for one
     * @param completedAt when the run stopped, whether it finished or failed; null while it is still going
     * @param deliveryCommentId the vendor's own identifier for the comment the run's summary landed in, or null
     *     when the run delivered no comment; what an adapter turns into an address is the adapter's to decide
     */
    record ReviewRunFacts(
            ReviewRunState state,
            TriggerMode triggerMode,
            @Nullable String lead,
            @Nullable Integer practicesEvaluated,
            @Nullable Integer practicesEligible,
            @Nullable Long durationSeconds,
            @Nullable Instant startedAt,
            @Nullable Instant completedAt,
            @Nullable String deliveryCommentId) {

        /**
         * When a surface dates this run: the moment it stopped once it has stopped, and the moment it
         * began while it is still going. One home, because the developer's own run list and the profile's
         * latest-run chip both date a run and must not disagree about what its time means.
         *
         * <p>Not the moment an observation was written. A run writes every observation it has in one pass
         * partway through, so that time is neither the start nor the end and reads as a lie beside a run
         * the reader watched finish minutes later. It stays the fallback all the same: a run whose own
         * edges were never recorded has nothing else to be dated by.
         */
        public Instant reviewedAt(Instant fallback) {
            if (completedAt != null) {
                return completedAt;
            }
            return startedAt == null ? fallback : startedAt;
        }
    }
}
