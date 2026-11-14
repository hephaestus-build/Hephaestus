package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.result.ResultFreshness;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCaptureDecision;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewFreshness;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewPracticeStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReviewFreshnessService implements ReviewFreshness {
    private static final Logger log = LoggerFactory.getLogger(ReviewFreshnessService.class);

    private final ReviewRepository reviews;
    private final ReviewItemRepository items;
    private final ReviewPracticeRepository practices;
    private final ReviewInvocationFence fence;
    private final Clock clock;
    private final JsonMapper json;
    private final ResultFreshness freshness;

    @Override
    @Transactional(readOnly = true)
    public Currentness capturedCurrentness(long workspaceId, UUID reviewId) {
        var review = reviews.findByIdAndWorkspaceId(reviewId, workspaceId).orElseThrow();
        if (review.getKind() == ReviewKind.SCAN) return Currentness.CURRENT;
        var combined = json.createObjectNode();
        var fingerprints = combined.putObject(ReviewCaptureDecision.SOURCE_FINGERPRINTS);
        for (var assignment : practices.findByWorkspaceIdAndReviewIdOrderByPracticeIdAsc(workspaceId, reviewId)) {
            var facts = assignment.getCaptureDecision();
            if (facts == null) continue;
            var sources = facts.path(ReviewCaptureDecision.SOURCE_FINGERPRINTS);
            if (!sources.isObject()) continue;
            for (var entry : sources.properties()) {
                var previous = fingerprints.get(entry.getKey());
                if (previous != null && !previous.equals(entry.getValue())) return Currentness.UNVERIFIABLE;
                fingerprints.set(entry.getKey(), entry.getValue());
            }
        }
        return freshness.capture(workspaceId, review.getItemId(), occasion(review), combined);
    }

    @Override
    @Transactional
    public boolean start(long workspaceId, ReviewAssignmentId id, UUID jobId, int attempt) {
        var review = fence.lock(workspaceId, id.reviewId(), jobId, attempt);
        var assignment = practices.lockAssignments(workspaceId, id.reviewId()).stream()
                .filter(row -> row.getPracticeId() == id.practiceId())
                .findFirst()
                .orElseThrow();
        var member = items.lockAssignment(workspaceId, id.reviewId(), id.practiceId(), id.itemId())
                .orElseThrow();
        if (!jobId.equals(member.getJobId()))
            throw new IllegalArgumentException("The invocation does not own this practice");
        if (member.getState() != ReviewItemState.LISTED) return false;
        if (review.getKind() == ReviewKind.SCAN) return true;
        var check = freshness.check(workspaceId, id.itemId(), occasion(review), assignment.getCaptureDecision());
        var state = check.currentness();
        log.info(
                "Practice start: reviewId={}, practiceId={}, currentness={}, changedSources={}, unreadSources={}",
                id.reviewId(),
                id.practiceId(),
                state,
                check.changed(),
                check.unread());
        if (state == Currentness.CURRENT) {
            assignment.setStatus(ReviewPracticeStatus.RUNNING);
            return true;
        }
        var reason = state == Currentness.STALE ? "REPLACED" : "READINESS_REFUSED";
        member.withhold(
                reason,
                state == Currentness.STALE
                        ? "The sources changed before this practice started."
                        : "The current sources could not be verified before this practice started.",
                clock.instant());
        assignment.setStatus(ReviewPracticeStatus.WITHHELD);
        assignment.setReason(reason);
        return false;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void syncSources(long workspaceId, ReviewAssignmentId id) {
        var review = reviews.findByIdAndWorkspaceId(id.reviewId(), workspaceId).orElse(null);
        if (review == null || review.getKind() == ReviewKind.SCAN) return;
        practices.findByWorkspaceIdAndReviewIdOrderByPracticeIdAsc(workspaceId, id.reviewId()).stream()
                .filter(row -> row.getPracticeId() == id.practiceId())
                .findFirst()
                .ifPresent(assignment ->
                        freshness.sync(workspaceId, id.itemId(), occasion(review), assignment.getCaptureDecision()));
    }

    private static @Nullable UUID occasion(Review review) {
        return ReviewSnapshot.occasion(review.getKind(), review.getOrigin(), review.getEventId());
    }
}
