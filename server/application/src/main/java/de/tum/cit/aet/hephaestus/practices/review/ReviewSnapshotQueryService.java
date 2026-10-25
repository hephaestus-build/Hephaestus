package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewPractice;
import de.tum.cit.aet.hephaestus.practices.spi.PinnedPractice;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@Primary
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class ReviewSnapshotQueryService implements ReviewSnapshotQuery {
    private final ReviewRepository reviews;
    private final ReviewPracticeRepository assignments;
    private final PracticeVersionRepository versions;
    private final ProductItemQuery items;
    private final ProductItemRepository itemRows;
    private final JsonMapper json;

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> publicReviewedAt(long workspaceId, UUID reviewId) {
        return reviews.findByIdAndWorkspaceId(reviewId, workspaceId)
                .flatMap(review -> Optional.ofNullable(review.getPublicReviewAt()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BigDecimal> budgetUsd(long workspaceId, UUID reviewId) {
        return reviews.findByIdAndWorkspaceId(reviewId, workspaceId)
                .flatMap(review -> Optional.ofNullable(review.getBudgetUsd()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewSnapshot> find(long workspaceId, UUID reviewId) {
        return reviews.findByIdAndWorkspaceId(reviewId, workspaceId).flatMap(review -> {
            var ref = items.find(workspaceId, review.getItemId());
            if (ref.isEmpty()) return Optional.empty();
            var rows = assignments.findByWorkspaceIdAndReviewIdOrderByPracticeIdAsc(workspaceId, reviewId);
            var pinned = pinned(workspaceId, rows, versionsOf(workspaceId, rows));
            var eventId = review.getOrigin() == ReviewOrigin.EVENT ? review.getEventId() : null;
            return Optional.of(new ReviewSnapshot(
                    reviewId,
                    workspaceId,
                    ref.orElseThrow(),
                    review.getKind(),
                    review.getItemVersion() == null
                            ? null
                            : new ItemVersion(review.getItemVersion(), review.getBaseVersion()),
                    review.getOrigin(),
                    review.getRolloutRevision(),
                    pinned,
                    eventId,
                    eventId == null
                            ? null
                            : itemRows.event(workspaceId, review.getItemId(), eventId)
                                    .getEvidenceExpiresAt()));
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<PinnedPractice>> findPinned(long workspaceId, Collection<UUID> reviewIds) {
        if (reviewIds.isEmpty()) return Map.of();
        var found = reviews.findByWorkspaceIdAndIdIn(workspaceId, reviewIds);
        // find finds a review only while its work is active.
        var active = itemRows
                .items(
                        workspaceId,
                        found.stream().map(Review::getItemId).distinct().toList())
                .stream()
                .filter(row -> row.getRemovedAt() == null)
                .map(ProductItemRepository.ItemRow::getId)
                .collect(Collectors.toSet());
        var shown = found.stream()
                .filter(review -> active.contains(review.getItemId()))
                .map(Review::getId)
                .toList();
        if (shown.isEmpty()) return Map.of();
        var rows = assignments.findByWorkspaceIdAndReviewIdInOrderByReviewIdAscPracticeIdAsc(workspaceId, shown);
        var versionById = versionsOf(workspaceId, rows);
        var byReview = rows.stream().collect(Collectors.groupingBy(ReviewPractice::getReviewId));
        Map<UUID, List<PinnedPractice>> pinned = new HashMap<>();
        shown.forEach(id -> pinned.put(id, pinned(workspaceId, byReview.getOrDefault(id, List.of()), versionById)));
        return pinned;
    }

    private Map<Long, PracticeVersion> versionsOf(long workspaceId, List<ReviewPractice> rows) {
        var ids = rows.stream()
                .map(ReviewPractice::getPracticeVersionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();
        return versions.findByWorkspaceIdAndIdIn(workspaceId, ids).stream()
                .collect(Collectors.toMap(version -> Objects.requireNonNull(version.getId()), Function.identity()));
    }

    /** A review's executable assignments: a pinned version of the same practice and a recorded method. */
    private List<PinnedPractice> pinned(
            long workspaceId, List<ReviewPractice> rows, Map<Long, PracticeVersion> versionById) {
        var pinned = new ArrayList<PinnedPractice>();
        for (var assignment : rows) {
            if (assignment.getPracticeVersionId() == null || assignment.getMethod() == null) continue;
            var version = Optional.ofNullable(versionById.get(assignment.getPracticeVersionId()))
                    .filter(v -> v.getWorkspaceId() == workspaceId
                            && v.getPractice().getId().equals(assignment.getPracticeId()));
            if (version.isPresent()) {
                var v = version.orElseThrow();
                pinned.add(new PinnedPractice(
                        assignment.getPracticeId(),
                        assignment.getPracticeVersionId(),
                        json.convertValue(v.getDefinition(), PracticeDefinition.class),
                        v.getReviewDigest(),
                        assignment.getMethod()));
            }
        }
        return List.copyOf(pinned);
    }
}
