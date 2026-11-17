package de.tum.cit.aet.hephaestus.practices.result.trend;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.result.ResultView;
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
 * Derived from each item's newest live review after the feedback was prepared.
 * {@link #CLEAN_NEEDED} consecutive clean opportunities resolve the feedback; opportunities without
 * a verdict are skipped, and a problem resets the clean count. Resolution survives subsequent problems.
 *
 * @param cleanWork the current clean run, oldest first, or the resolving run once resolved
 * @param resolvedAt the resolving review's time, or null while unresolved
 * @param problemWork problems since the last clean opportunity, newest first; empty during a clean run
 */
public record WorkResolution(List<Work> cleanWork, @Nullable Instant resolvedAt, List<Work> problemWork) {

    public static final int CLEAN_NEEDED = 3;

    public static final WorkResolution NONE = new WorkResolution(List.of(), null, List.of());

    /**
     * One practice's opportunities since a horizon, bundled once and read by every piece of feedback about
     * the practice, so a page with several cards on one practice bundles its work once rather than per card.
     */
    public static final class Opportunities {

        private final List<EvidenceOpportunity> oldestFirst;

        private Opportunities(List<EvidenceOpportunity> oldestFirst) {
            this.oldestFirst = oldestFirst;
        }

        /** @param horizon the moment before which a result is not an opportunity at all */
        public static Opportunities of(List<ResultView> practiceResults, Instant horizon) {
            return new Opportunities(
                    OpportunityBundler.opportunities(practiceResults, horizon).reversed());
        }

        public WorkResolution resolve(Instant preparedAt) {
            List<Work> clean = new ArrayList<>();
            List<Work> problems = new ArrayList<>();
            for (EvidenceOpportunity opportunity : oldestFirst) {
                if (!opportunity.occurredAt().isAfter(preparedAt) || !opportunity.decided()) {
                    continue;
                }
                Work work = new Work(
                        opportunity.itemKind(), opportunity.itemId(), opportunity.reviewId(), opportunity.occurredAt());
                if (!opportunity.clean()) {
                    clean.clear();
                    problems.add(work);
                    continue;
                }
                problems.clear();
                clean.add(work);
                if (clean.size() == CLEAN_NEEDED) {
                    return new WorkResolution(List.copyOf(clean), opportunity.occurredAt(), List.of());
                }
            }
            return clean.isEmpty() && problems.isEmpty()
                    ? NONE
                    : new WorkResolution(List.copyOf(clean), null, Work.newestFirst(problems.stream()));
        }
    }

    public record Work(ItemKind kind, long itemId, UUID reviewId, Instant at) {
        public static Work of(ResultView view) {
            return new Work(
                    new ItemKind(view.item().kind()),
                    view.result().itemId(),
                    view.result().reviewId(),
                    view.result().recordedAt());
        }

        public long key() {
            return itemId;
        }

        /** One entry per piece of work, at its newest review, newest first. */
        public static List<Work> newestFirst(Stream<Work> work) {
            Map<Long, Work> byWork = new LinkedHashMap<>();
            work.forEach(ref ->
                    byWork.merge(ref.key(), ref, (left, right) -> left.at().isAfter(right.at()) ? left : right));
            return byWork.values().stream()
                    .sorted(Comparator.comparing(Work::at).reversed())
                    .toList();
        }
    }
}
