package de.tum.cit.aet.hephaestus.practices.spi;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Provides persisted review outcomes without exposing agent-job persistence to the practices module. */
public interface ReviewOutcomeLookup {
    /** @return outcomes by review id; a run this workspace does not own is simply absent */
    Map<UUID, ReviewOutcome> findByIds(long workspaceId, Collection<UUID> reviewIds);

    /**
     * @param insufficientEvidence the run completed without any model executing because the evidence it
     *                             needed was not readable — distinct from failure, since nothing broke
     * @param readinessByPracticeSlug eligibility decision by practice slug
     * @param coverageByPracticeSlug outcome for each eligible practice; empty without a valid account
     * @param answeredByPracticeSlug the completed review whose answer this run reused instead of asking, by
     *                               practice slug, as the run recorded it when it was prepared
     */
    record ReviewOutcome(
            @NonNull ReviewRunState state,
            boolean insufficientEvidence,
            @Nullable Instant decidedAt,
            @NonNull Map<String, PracticeReadinessOutcome> readinessByPracticeSlug,
            @NonNull Map<String, PracticeCoverageOutcome> coverageByPracticeSlug,
            @NonNull Map<String, UUID> answeredByPracticeSlug) {
        public ReviewOutcome(
                ReviewRunState state,
                boolean insufficientEvidence,
                @Nullable Instant decidedAt,
                Map<String, PracticeReadinessOutcome> readinessByPracticeSlug,
                Map<String, PracticeCoverageOutcome> coverageByPracticeSlug) {
            this(state, insufficientEvidence, decidedAt, readinessByPracticeSlug, coverageByPracticeSlug, Map.of());
        }
    }

    enum PracticeCoverageOutcome {
        EVALUATED,
        NOT_REACHED,
    }

    /**
     * @param blockers      already-rendered sentences naming what could not be read, so no consumer has to
     *                      learn the evidence vocabulary to explain a refusal
     * @param limitation    set when the practice itself declares it is not reviewed automatically — a
     *                      sentence saying so. A policy stop, not a capture failure: nothing was missing,
     *                      and reporting it as missing evidence would send somebody to fix a capture that
     *                      worked
     * @param notApplicable set when the run read the evidence and the thing this practice judges was not
     *                      in the work — the practice author's own sentence for it. Distinct from a
     *                      blocker, and never both: a blocker is a fact about our instrument, this is a
     *                      fact about the work, and telling somebody "we could not look" when we looked
     *                      and there was nothing of this kind to see sends them to the wrong fix
     */
    record PracticeReadinessOutcome(
            boolean ready,
            @NonNull List<String> blockers,
            @Nullable String limitation,
            @Nullable String notApplicable) {}

    /**
     * Deliberately coarser than the orchestrator's job status: a trace has nothing different to say
     * about a run that timed out and one that was cancelled, and a distinction nobody renders drifts.
     */
    enum ReviewRunState {
        IN_PROGRESS,
        COMPLETED,
        FAILED,
    }
}
