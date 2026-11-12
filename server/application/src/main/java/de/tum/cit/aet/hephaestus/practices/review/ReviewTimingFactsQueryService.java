package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemMergeTimeQuery;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewTimingFact;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewTimingFacts;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewTimingFactsQuery;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewTimingFactsQueryService implements ReviewTimingFactsQuery {
    private static final int PAGE_SIZE = 1000;
    private final ReviewTimingRepository repository;
    private final ItemMergeTimeQuery mergeTimes;

    @Override
    public List<ReviewTimingFact> window(long workspaceId, Instant from, Instant to) {
        if (!from.isBefore(to)) throw new IllegalArgumentException("Use a nonempty timing range.");
        var output = new ArrayList<ReviewTimingFact>();
        ReviewTimingRepository.CursorRow after = null;
        while (true) {
            var rows = repository.window(
                    workspaceId,
                    from,
                    to,
                    after == null ? null : after.getCreatedAt(),
                    after == null ? null : after.getId(),
                    PageRequest.of(0, PAGE_SIZE));
            output.addAll(timelines(
                    workspaceId,
                    rows.stream().map(ReviewTimingRepository.CursorRow::getId).toList()));
            if (rows.size() < PAGE_SIZE) return List.copyOf(output);
            after = rows.getLast();
        }
    }

    @Override
    public ReviewTimingFacts facts(long workspaceId, List<UUID> reviewIds, Instant from, Instant to) {
        if (!from.isBefore(to)) throw new IllegalArgumentException("Use a nonempty timing range.");
        double asked = 0;
        double finishedAsked = 0;
        long afterMerge = 0;
        long startedByMerge = 0;
        for (var fact : timelines(workspaceId, reviewIds)) {
            var delay = asked(fact);
            if (delay != null) {
                if (fact.firstAutomaticPostedAt() != null) asked += delay;
                if (fact.finished()) finishedAsked += delay;
            }
            var completed = fact.completedAt();
            var merged = fact.mergedAt();
            if (fact.finished() && completed != null && merged != null && merged.isBefore(completed)) {
                afterMerge++;
                if (fact.startedByMerge()) startedByMerge++;
            }
        }
        long withheld = repository.withheldAfterMerge(workspaceId, from, to);
        return new ReviewTimingFacts(asked, finishedAsked, afterMerge, startedByMerge, withheld);
    }

    @Override
    public List<ReviewTimingFact> timelines(long workspaceId, List<UUID> reviewIds) {
        var ids = reviewIds.stream()
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        var output = new ArrayList<ReviewTimingFact>();
        for (int start = 0; start < ids.size(); start += PAGE_SIZE) {
            var rows =
                    repository
                            .timelines(workspaceId, ids.subList(start, Math.min(start + PAGE_SIZE, ids.size())))
                            .stream()
                            .map(row -> row(workspaceId, row))
                            .toList();
            var merged = mergeTimes.mergedAt(
                    workspaceId, rows.stream().map(Row::item).distinct().toList());
            for (var row : rows) {
                var fact = row.fact();
                output.add(new ReviewTimingFact(
                        fact.reviewId(),
                        fact.origin(),
                        fact.createdAt(),
                        fact.eventAt(),
                        fact.completedAt(),
                        fact.publicCompletedAt(),
                        fact.firstAutomaticPostedAt(),
                        fact.firstReadAt(),
                        merged.get(row.item().itemId()),
                        fact.finished(),
                        fact.startedByMerge()));
            }
        }
        return List.copyOf(output);
    }

    private static Row row(long workspaceId, ReviewTimingRepository.TimelineRow row) {
        var completed = row.getCompletedAt();
        return new Row(
                new ItemRef(
                        workspaceId,
                        row.getItemId(),
                        new ItemKind(row.getKind()),
                        row.getSourceId(),
                        row.getParentId(),
                        row.getPath()),
                new ReviewTimingFact(
                        row.getId(),
                        ReviewOrigin.valueOf(row.getOrigin()),
                        row.getCreatedAt(),
                        row.getEventAt(),
                        completed,
                        row.getPublicCompletedAt(),
                        row.getFirstPostedAt(),
                        row.getFirstReadAt(),
                        null,
                        completed != null
                                && (row.getStatus().equals("COMPLETE")
                                        || row.getStatus().equals("PARTIAL")),
                        row.getStartedByMerge()));
    }

    private static @Nullable Double asked(ReviewTimingFact fact) {
        if (fact.origin() != ReviewOrigin.EVENT) return 0.0;
        var event = fact.eventAt();
        if (event == null) return null;
        var delay = Duration.between(event, fact.createdAt());
        return delay.isNegative() ? 0.0 : delay.getSeconds() + delay.getNano() / 1_000_000_000.0;
    }

    private record Row(ItemRef item, ReviewTimingFact fact) {}
}
