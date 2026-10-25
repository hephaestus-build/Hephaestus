package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.spi.PinnedPractice;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnCaptureRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewAssignmentQueryService implements ReviewAssignmentQuery {
    private final ReviewRepository reviews;
    private final ReviewPracticeRepository assignments;
    private final ReviewItemRepository items;
    private final PracticeRepository practices;
    private final PracticeVersionRepository versions;
    private final ProductItemQuery itemQuery;
    private final JsonMapper json;

    @Override
    public List<ReviewAssignmentId> executable(long workspaceId, UUID reviewId, long itemId) {
        return items.executablePracticeIds(workspaceId, reviewId, itemId).stream()
                .map(practiceId -> new ReviewAssignmentId(reviewId, practiceId, itemId))
                .filter(id -> find(workspaceId, id).isPresent())
                .toList();
    }

    @Override
    public Optional<ReviewAssignmentSnapshot> find(long workspaceId, ReviewAssignmentId id) {
        var review = reviews.findByIdAndWorkspaceId(id.reviewId(), workspaceId);
        var assignment =
                assignments.findByWorkspaceIdAndReviewIdAndPracticeId(workspaceId, id.reviewId(), id.practiceId());
        var item = items.findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
                workspaceId, id.reviewId(), id.practiceId(), id.itemId());
        if (review.isEmpty() || assignment.isEmpty() || item.isEmpty()) return Optional.empty();
        var pinned = assignment.orElseThrow();
        var member = item.orElseThrow();
        var parent = review.orElseThrow();
        if (pinned.getPracticeVersionId() == null || pinned.getMethod() == null || member.getItemVersion() == null)
            return Optional.empty();
        var version = versions.findByIdAndWorkspaceId(pinned.getPracticeVersionId(), workspaceId)
                .filter(v -> v.getWorkspaceId() == workspaceId
                        && v.getPractice().getId().equals(id.practiceId()));
        var practice = practices.findByIdAndWorkspaceId(id.practiceId(), workspaceId);
        var ref = itemQuery.find(workspaceId, id.itemId());
        if (version.isEmpty() || practice.isEmpty() || ref.isEmpty()) return Optional.empty();
        if (!version.orElseThrow().getDefinition().has("about")) return Optional.empty();
        var definition = json.convertValue(version.orElseThrow().getDefinition(), PracticeDefinition.class);
        var detail = member.getDetail();
        if (parent.getKind() == ReviewKind.ITEM && (detail == null || !detail.has("developerId")))
            return Optional.empty();
        Long developer = parent.getKind() == ReviewKind.SCAN
                        || detail == null
                        || detail.path("developerId").isNull()
                ? null
                : detail.path("developerId").longValue();
        if (parent.getKind() == ReviewKind.ITEM && definition.about() != null && developer == null)
            return Optional.empty();
        return Optional.of(new ReviewAssignmentSnapshot(
                id,
                ref.orElseThrow(),
                new ItemVersion(
                        member.getItemVersion(),
                        parent.getKind() == ReviewKind.SCAN ? parent.getItemVersion() : parent.getBaseVersion()),
                developer,
                practice.orElseThrow().getSlug(),
                new PinnedPractice(
                        id.practiceId(),
                        pinned.getPracticeVersionId(),
                        definition,
                        version.orElseThrow().getReviewDigest(),
                        pinned.getMethod())));
    }

    /**
     * Reads only the review, its rows and the workspace's items: the attribution is the developer the review wrote on
     * its row when it listed the item, never the job's own input. Recorded keys are read in one scoped query and each
     * item once. The selection is all or nothing: a key of another review, a recorded key without its row, or any
     * selected row without a readable item or attribution leaves the whole run unattributed.
     */
    @Override
    public List<Retained> retained(long workspaceId, UUID reviewId, UUID jobId, Collection<ReviewAssignmentId> ids) {
        return retained(workspaceId, List.of(new RetainedRun(reviewId, jobId, ids)))
                .getOrDefault(jobId, List.of());
    }

    @Override
    public Map<UUID, List<Retained>> retained(long workspaceId, Collection<RetainedRun> runs) {
        var readable = runs.stream()
                .filter(run -> run.ids().stream().allMatch(id -> id.reviewId().equals(run.reviewId())))
                .toList();
        if (readable.isEmpty()) return Map.of();
        var reviewById = reviews
                .findByWorkspaceIdAndIdIn(
                        workspaceId,
                        readable.stream().map(RetainedRun::reviewId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Review::getId, Function.identity()));
        // Only each run's own rows: a scan's review holds every file it lists, and a run reviews one of them.
        var known = readable.stream()
                .filter(run -> reviewById.containsKey(run.reviewId()))
                .toList();
        Map<ReviewItem.Id, ReviewItem> selected = new LinkedHashMap<>();
        var keyed = known.stream()
                .flatMap(run -> run.ids().stream())
                .map(id -> new ReviewedItem(id.reviewId(), id.itemId()))
                .distinct()
                .toList();
        if (!keyed.isEmpty())
            items.findByWorkspaceIdAndReviewedItems(
                            workspaceId,
                            keyed.stream().map(ReviewedItem::reviewId).toArray(UUID[]::new),
                            keyed.stream().map(ReviewedItem::itemId).toArray(Long[]::new))
                    .forEach(row -> selected.put(
                            new ReviewItem.Id(row.getReviewId(), row.getPracticeId(), row.getItemId()), row));
        var bound = known.stream()
                .filter(run -> run.ids().isEmpty())
                .map(RetainedRun::jobId)
                .toList();
        if (!bound.isEmpty())
            items.findByWorkspaceIdAndJobIdIn(workspaceId, bound)
                    .forEach(row -> selected.put(
                            new ReviewItem.Id(row.getReviewId(), row.getPracticeId(), row.getItemId()), row));
        Map<UUID, List<ReviewItem>> rowsByReview =
                selected.values().stream().collect(Collectors.groupingBy(ReviewItem::getReviewId));
        var refs = itemQuery.findAll(
                workspaceId,
                rowsByReview.values().stream()
                        .flatMap(List::stream)
                        .map(ReviewItem::getItemId)
                        .distinct()
                        .toList());
        Map<UUID, List<Retained>> retained = new HashMap<>();
        for (var run : readable) {
            var review = reviewById.get(run.reviewId());
            if (review == null) continue;
            var reviewRows = rowsByReview.getOrDefault(run.reviewId(), List.of());
            List<ReviewItem> rows;
            if (run.ids().isEmpty()) {
                rows = reviewRows.stream()
                        .filter(row -> run.jobId().equals(row.getJobId()))
                        .sorted(BY_ASSIGNMENT)
                        .toList();
            } else {
                var wanted = Set.copyOf(run.ids());
                rows = reviewRows.stream()
                        .filter(row -> wanted.contains(
                                new ReviewAssignmentId(run.reviewId(), row.getPracticeId(), row.getItemId())))
                        .sorted(BY_ASSIGNMENT)
                        .toList();
                if (rows.size() != wanted.size()) continue;
            }
            attributed(run.reviewId(), review.getKind() == ReviewKind.SCAN, rows, refs)
                    .ifPresent(list -> retained.put(run.jobId(), list));
        }
        return retained;
    }

    private static final Comparator<ReviewItem> BY_ASSIGNMENT =
            Comparator.comparingLong(ReviewItem::getPracticeId).thenComparingLong(ReviewItem::getItemId);

    /** The run's rows as retained assignments, or empty when a row's item or attribution cannot be read. */
    private Optional<List<Retained>> attributed(
            UUID reviewId, boolean scan, List<ReviewItem> rows, Map<Long, ItemRef> refs) {
        var retained = new ArrayList<Retained>();
        for (var row : rows) {
            var ref = refs.get(row.getItemId());
            if (ref == null) return Optional.empty();
            Long developer = null;
            if (!scan) {
                var named = attribution(row.getDetail());
                if (named.isEmpty()) return Optional.empty();
                developer = named.orElseThrow().developerId();
            }
            retained.add(new Retained(
                    new ReviewAssignmentId(reviewId, row.getPracticeId(), row.getItemId()), ref, developer));
        }
        return Optional.of(List.copyOf(retained));
    }

    /**
     * Reads the asked reviews and the rows of exactly the asked pairs, in one scoped query each. A pair is answered only
     * where its review has a row for its item. Rows are attributed as {@link #retained} reads them.
     */
    @Override
    public Map<ReviewedItem, Recorded> recorded(long workspaceId, long developerId, Collection<ReviewedItem> reviewed) {
        if (reviewed.isEmpty()) return Map.of();
        var asked = List.copyOf(Set.copyOf(reviewed));
        Map<UUID, Review> parents = reviews
                .findByWorkspaceIdAndIdIn(
                        workspaceId, asked.stream().map(ReviewedItem::reviewId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Review::getId, Function.identity()));
        Map<ReviewedItem, List<ReviewItem>> rows = items
                .findByWorkspaceIdAndReviewedItems(
                        workspaceId,
                        asked.stream().map(ReviewedItem::reviewId).toArray(UUID[]::new),
                        asked.stream().map(ReviewedItem::itemId).toArray(Long[]::new))
                .stream()
                .collect(Collectors.groupingBy(row -> new ReviewedItem(row.getReviewId(), row.getItemId())));
        var recorded = new HashMap<ReviewedItem, Recorded>();
        for (var key : asked) {
            var review = parents.get(key.reviewId());
            var members = rows.get(key);
            if (review == null || members == null) continue;
            recorded.put(
                    key,
                    new Recorded(
                            review.getStatus(),
                            review.getStopReason(),
                            review.getKind() == ReviewKind.ITEM ? counts(members, developerId) : null));
        }
        return Map.copyOf(recorded);
    }

    /**
     * The developer's rows among one reviewed item's rows, by state. A row naming nobody or another developer is not
     * theirs. One row that cannot say whose work it was could be theirs, so then nothing is counted, as when none is.
     */
    private static @Nullable AssignmentCounts counts(List<ReviewItem> rows, long developerId) {
        long judged = 0;
        long reused = 0;
        long withheld = 0;
        long pending = 0;
        for (var row : rows) {
            var named = attribution(row.getDetail());
            if (named.isEmpty()) return null;
            if (!Long.valueOf(developerId).equals(named.orElseThrow().developerId())) continue;
            switch (row.getState()) {
                case JUDGED -> judged++;
                case REUSED -> reused++;
                case WITHHELD -> withheld++;
                case LISTED -> pending++;
            }
        }
        long total = judged + reused + withheld + pending;
        return total == 0 ? null : new AssignmentCounts(total, judged, reused, withheld, pending);
    }

    /** Whose work an ITEM review's row names; {@code developerId} is null for nobody. Empty when it cannot say. */
    private static Optional<Attribution> attribution(@Nullable JsonNode detail) {
        if (detail == null || !detail.has("developerId")) return Optional.empty();
        var named = detail.path("developerId");
        if (named.isNull()) return Optional.of(new Attribution(null));
        if (!named.isIntegralNumber() || !named.canConvertToLong()) return Optional.empty();
        return Optional.of(new Attribution(named.longValue()));
    }

    private record Attribution(@Nullable Long developerId) {}
}
