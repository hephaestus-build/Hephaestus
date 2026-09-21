package de.tum.cit.aet.hephaestus.practices.observation.trend;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * One comparable practice opportunity, normalized across all observations in the latest run of one piece of reviewed work.
 *
 * <p>Carries no practice slug. The owning {@link PracticeTrend} already names the scope this list belongs to,
 * and the group aggregation merges opportunities that several practices saw on the same artifact — a merged
 * one answers to no single practice. A field nothing reads is a field that can only go stale or, worse, be
 * filtered on later as though it meant something.
 *
 * @param jobId the run the opportunity was read off: the newest one that reviewed this piece of work
 */
record EvidenceOpportunity(
        @NonNull ArtifactKind artifactKind,
        long artifactId,
        @NonNull UUID jobId,
        @NonNull Instant occurredAt,
        @NonNull OutcomeVector outcomes,
        @NonNull TrendBundle bundle) {
    boolean applicable() {
        return outcomes.applicable() > 0;
    }

    /** A verdict that raised no problem. Verdictless opportunities are neither clean nor dirty. */
    boolean clean() {
        return applicable() && outcomes.negatives() == 0;
    }

    EvidenceOpportunity withBundle(TrendBundle value) {
        return new EvidenceOpportunity(artifactKind, artifactId, jobId, occurredAt, outcomes, value);
    }
}
