package de.tum.cit.aet.hephaestus.practices.observation.trend;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * How a developer's own work resolves a piece of practice feedback: after the feedback was prepared,
 * {@link #CLEAN_NEEDED} pieces of reviewed work in a row come back clean on the practice.
 *
 * <p>Read at request time off the observations and never stored, so a re-review that changes what a piece
 * of work's latest run said changes the answer with it. The unit is a piece of reviewed work, bundled as the
 * trend bundles it: one opportunity per piece of work, read off its latest run, so a pull request reviewed
 * three times is one piece of work, not three. An opportunity that produced no verdict is skipped rather than
 * counted as either side, exactly as {@link PracticeTrend#cleanWork()} skips it; one that raised a
 * problem starts the count over. The first run of {@link #CLEAN_NEEDED} clean opportunities resolves the
 * feedback, and what came after it does not un-resolve it: a later slip is new feedback's business.
 *
 * @param cleanWork the pieces of work in the current clean run, oldest first, at most {@link #CLEAN_NEEDED};
 *     empty again after a problem, and exactly the resolving pieces once the feedback is resolved
 * @param resolvedAt when the piece of work that completed the run was reviewed, or null while the work has
 *     not resolved the feedback
 */
public record WorkResolution(List<Work> cleanWork, @Nullable Instant resolvedAt) {

    /** Clean pieces of work in a row that resolve a piece of feedback. */
    public static final int CLEAN_NEEDED = 3;

    /** No clean work yet. */
    public static final WorkResolution NONE = new WorkResolution(List.of(), null);

    /**
     * @param observations the practice's observations about the developer, any order; those observed at or
     *     before {@code preparedAt} are ignored, since they are what the feedback was written from
     * @param preparedAt when the feedback was prepared
     */
    public static WorkResolution of(List<Observation> observations, Instant preparedAt) {
        return Opportunities.of(observations, preparedAt).resolve(preparedAt);
    }

    /**
     * One practice's opportunities since a horizon, bundled once and read by every piece of feedback about
     * the practice, so a page with several cards on one habit bundles its work once rather than per card.
     */
    public static final class Opportunities {

        private final List<EvidenceOpportunity> oldestFirst;

        private Opportunities(List<EvidenceOpportunity> oldestFirst) {
            this.oldestFirst = oldestFirst;
        }

        /** @param horizon the moment before which an observation is not an opportunity at all */
        public static Opportunities of(List<Observation> observations, Instant horizon) {
            return new Opportunities(
                    OpportunityBundler.opportunities(observations, horizon).reversed());
        }

        /** How the work has answered feedback prepared at {@code preparedAt}. */
        public WorkResolution resolve(Instant preparedAt) {
            List<Work> clean = new ArrayList<>();
            for (EvidenceOpportunity opportunity : oldestFirst) {
                if (!opportunity.occurredAt().isAfter(preparedAt) || !opportunity.applicable()) {
                    continue;
                }
                if (!opportunity.clean()) {
                    clean.clear();
                    continue;
                }
                clean.add(new Work(
                        opportunity.artifactKind(),
                        opportunity.artifactId(),
                        opportunity.jobId(),
                        opportunity.occurredAt()));
                if (clean.size() == CLEAN_NEEDED) {
                    return new WorkResolution(List.copyOf(clean), opportunity.occurredAt());
                }
            }
            return clean.isEmpty() ? NONE : new WorkResolution(List.copyOf(clean), null);
        }
    }

    /**
     * One piece of reviewed work as the resolution and profile rules see it: its identity, the run that
     * reviewed it and when. The job id is what later resolves the label and the link; the rules themselves
     * never need them.
     */
    public record Work(ArtifactKind kind, long id, UUID jobId, Instant at) {
        public static Work of(Observation observation) {
            return new Work(
                    observation.getArtifactKind(),
                    observation.getArtifactId(),
                    observation.getAgentJobId(),
                    observation.getObservedAt());
        }

        /** One entry per piece of work, at its newest review, newest first. */
        public static List<Work> newestFirst(Stream<Work> work) {
            Map<Key, Work> byWork = new LinkedHashMap<>();
            work.forEach(ref ->
                    byWork.merge(Key.of(ref), ref, (left, right) -> left.at().isAfter(right.at()) ? left : right));
            return byWork.values().stream()
                    .sorted(Comparator.comparing(Work::at).reversed())
                    .toList();
        }

        /** Identity of a piece of work: the same pull request reviewed twice is one piece of work. */
        public record Key(ArtifactKind kind, long id) {
            public static Key of(Work work) {
                return new Key(work.kind(), work.id());
            }

            static Key of(EvidenceOpportunity opportunity) {
                return new Key(opportunity.artifactKind(), opportunity.artifactId());
            }
        }
    }
}
