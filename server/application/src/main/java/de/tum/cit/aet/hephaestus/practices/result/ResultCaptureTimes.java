package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.model.ResultFacts;
import de.tum.cit.aet.hephaestus.practices.review.ReviewItemRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustodyQuery;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The capture time of each permitted result's producing job. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class ResultCaptureTimes {
    private final ReviewItemRepository assignments;
    private final HistoryCustodyQuery custody;

    /**
     * By result id, the capture time of the job that produced each of {@code rows} that {@code checks} permits. A row
     * without a producing job or a recorded capture time is absent.
     */
    Map<UUID, Instant> of(long workspaceId, List<Result> rows, Map<UUID, ResultSourceAuthorization.Check> checks) {
        var producers =
                producingJobs(workspaceId, rows.stream().map(Result::facts).toList());
        Map<UUID, HistoryCustodyQuery.Receipt> receipts = new HashMap<>();
        for (var row : rows) {
            var check = checks.get(row.getId());
            var job = producers.get(row.getId());
            if (check != null && check.permitted() && job != null)
                receipts.put(row.getId(), new HistoryCustodyQuery.Receipt(row.getReviewId(), job));
        }
        var times = custody.capturedAt(workspaceId, Set.copyOf(receipts.values()));
        Map<UUID, Instant> byResult = new HashMap<>();
        receipts.forEach((resultId, receipt) -> {
            var at = times.get(receipt);
            if (at != null) byResult.put(resultId, at);
        });
        return byResult;
    }

    /**
     * The job that produced each of {@code rows}: the job of the assignment that the row's own review made for its
     * practice and item, when that assignment records exactly this result. A legacy assignment that records none, or
     * an assignment without a job, names no job. The custody's {@code sourceJobIds} also list copied dependencies, so
     * they cannot name it. The rows' assignments load in a bounded number of queries.
     */
    Map<UUID, UUID> producingJobs(long workspaceId, Collection<ResultFacts> rows) {
        var owned = rows.stream()
                .filter(result -> result.workspaceId() == workspaceId)
                .toList();
        var byAssignment = assignmentsOf(workspaceId, owned);
        Map<UUID, UUID> jobs = new HashMap<>();
        for (var result : owned) {
            var producer = byAssignment.get(new ReviewItem.Id(result.reviewId(), result.practiceId(), result.itemId()));
            if (producer == null || !ResultSourceAuthorization.produced(result, producer)) continue;
            var job = producer.getJobId();
            if (job != null) jobs.put(result.id(), job);
        }
        return jobs;
    }

    private Map<ReviewItem.Id, ReviewItem> assignmentsOf(long workspaceId, List<ResultFacts> owned) {
        Map<ReviewItem.Id, ReviewItem> byAssignment = new HashMap<>();
        for (var chunk : ResultSourceAuthorization.chunks(
                owned.stream().map(ResultFacts::reviewId).distinct().toList()))
            assignments
                    .findByWorkspaceIdAndReviewIdIn(workspaceId, chunk)
                    .forEach(m ->
                            byAssignment.put(new ReviewItem.Id(m.getReviewId(), m.getPracticeId(), m.getItemId()), m));
        return byAssignment;
    }
}
