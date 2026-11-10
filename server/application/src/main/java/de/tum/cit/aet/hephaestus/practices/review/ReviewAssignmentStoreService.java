package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.integration.core.spi.CandidateRefusal;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustodyQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCaptureDecision;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewPracticeStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Service
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class ReviewAssignmentStoreService implements ReviewAssignmentStore {
    private final ReviewRepository reviews;
    private final ReviewItemRepository items;
    private final ReviewStagedPayloadRepository payloads;
    private final ReviewPracticeRepository practices;
    private final ReviewInvocationFence fence;
    private final Clock clock;
    private final ReviewRuntimeQuery runtime;
    private final HistoryCustodyQuery custody;
    private final JsonMapper json;
    private final ReuseCandidateQuery reuseCandidates;

    @Override
    @Transactional
    public boolean publicReviewed(long workspaceId, UUID reviewId, UUID jobId, int attempt, Instant acceptedAt) {
        var review = fence.lock(workspaceId, reviewId, jobId, attempt);
        if (review.getKind() != ReviewKind.ITEM)
            throw new IllegalArgumentException("Only an item review has a public composition.");
        if (review.getPublicReviewAt() != null) return false;
        if (acceptedAt.isBefore(review.getCreatedAt()))
            throw new IllegalArgumentException("Public acceptance cannot precede the review request.");
        review.setPublicReviewAt(acceptedAt.truncatedTo(ChronoUnit.MICROS));
        return true;
    }

    @Override
    @Transactional
    public void bind(long workspaceId, UUID reviewId, UUID jobId, List<ReviewAssignmentId> assignments) {
        var review = reviews.lockByIdAndWorkspaceId(reviewId, workspaceId).orElseThrow();
        if (review.getStatus() != ReviewStatus.QUEUED && review.getStatus() != ReviewStatus.RUNNING)
            throw new IllegalStateException("The review is closed.");
        if (review.isCancelRequested()) throw new IllegalStateException("The review was cancelled.");
        if (assignments.isEmpty() || assignments.stream().distinct().count() != assignments.size())
            throw new IllegalArgumentException("Assignments must be nonempty and distinct.");
        for (var id : assignments.stream()
                .sorted(Comparator.comparingLong(ReviewAssignmentId::practiceId)
                        .thenComparingLong(ReviewAssignmentId::itemId))
                .toList()) {
            if (!id.reviewId().equals(reviewId))
                throw new IllegalArgumentException("The assignment belongs to another review.");
            var item = items.lockAssignment(workspaceId, reviewId, id.practiceId(), id.itemId())
                    .orElseThrow();
            if (item.getState() != ReviewItemState.LISTED
                    || (item.getJobId() != null
                            && !item.getJobId().equals(jobId)
                            && runtime.activeJobIds(workspaceId, reviewId).contains(item.getJobId())))
                throw new IllegalStateException("The assignment already has an invocation.");
            item.setJobId(jobId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> endedPractices(long workspaceId, UUID reviewId, UUID jobId) {
        return items
                .findByWorkspaceIdAndReviewIdAndJobIdOrderByPracticeIdAscItemIdAsc(workspaceId, reviewId, jobId)
                .stream()
                .filter(member -> member.getState() != ReviewItemState.LISTED)
                .map(ReviewItem::getPracticeId)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasStaged(long workspaceId, UUID reviewId, UUID jobId) {
        return items.existsByWorkspaceIdAndReviewIdAndJobIdAndState(
                workspaceId, reviewId, jobId, ReviewItemState.STAGED);
    }

    @Override
    @Transactional
    public Set<Long> capture(
            long workspaceId, UUID reviewId, UUID jobId, int attempt, List<ReviewCaptureDecision> decisions) {
        var reused = new HashSet<Long>();
        var review = fence.lock(workspaceId, reviewId, jobId, attempt);
        var copied = custody.find(workspaceId, List.of(jobId));
        var assignments = practices.lockAssignments(workspaceId, reviewId).stream()
                .collect(Collectors.toMap(p -> p.getPracticeId(), p -> p));
        if (decisions.stream().map(ReviewCaptureDecision::practiceId).distinct().count() != decisions.size())
            throw new IllegalArgumentException("Record each practice capture once.");
        for (var decision : decisions.stream()
                .sorted(Comparator.comparingLong(ReviewCaptureDecision::practiceId))
                .toList()) {
            var assignment = Objects.requireNonNull(assignments.get(decision.practiceId()));
            if (!decision.facts().isObject()
                    || (decision.reason() != null && decision.reason().length() > 48))
                throw new IllegalArgumentException("Capture facts require a bounded recorded reason.");
            if (decision.status() != ReviewPracticeStatus.READY
                    && decision.status() != ReviewPracticeStatus.WITHHELD
                    && decision.status() != ReviewPracticeStatus.FAILED)
                throw new IllegalArgumentException("Capture records readiness, refusal or failure.");
            var members = items.findByWorkspaceIdAndReviewIdAndPracticeIdAndJobIdOrderByItemIdAsc(
                    workspaceId, reviewId, decision.practiceId(), jobId);
            if (members.isEmpty())
                throw new IllegalArgumentException("The invocation owns no membership for this practice.");
            if (members.stream().anyMatch(m -> m.getState() != ReviewItemState.LISTED))
                throw new IllegalStateException("Capture cannot rewrite terminal membership.");
            if (review.getKind() == ReviewKind.ITEM) {
                assignment.setCaptureDecision(decision.facts());
                assignment.setStatus(decision.status());
                assignment.setReason(decision.reason());
            } else if (assignment.getStatus() == ReviewPracticeStatus.QUEUED
                    || assignment.getStatus() == ReviewPracticeStatus.READY) {
                // A scan records capture per file; its practice runs from the first file a worker captures.
                assignment.setStatus(ReviewPracticeStatus.RUNNING);
            }
            boolean refused = decision.status() == ReviewPracticeStatus.WITHHELD
                    || decision.status() == ReviewPracticeStatus.FAILED;
            if (refused && (decision.reason() == null || decision.reason().isBlank()))
                throw new IllegalArgumentException("A refused capture requires its reason.");
            for (var member : members) {
                var locked = items.lockAssignment(workspaceId, reviewId, decision.practiceId(), member.getItemId())
                        .orElseThrow();
                var detail = locked.getDetail() == null
                        ? JsonNodeFactory.instance.objectNode()
                        : (ObjectNode) locked.getDetail().deepCopy();
                detail.set("custody", json.valueToTree(copied));
                if (review.getKind() == ReviewKind.SCAN) detail.set("captureDecision", decision.facts());
                var reusable = reusable(workspaceId, review, decision, member.getItemId());
                if (reusable.isPresent()) {
                    detail.set(
                            "custody", json.valueToTree(reusable.orElseThrow().custody()));
                    locked.setState(ReviewItemState.REUSED);
                    locked.setResultId(reusable.orElseThrow().result().getId());
                    assignment.setStatus(ReviewPracticeStatus.DONE);
                    reused.add(decision.practiceId());
                }
                locked.setDetail(detail);
                if (refused) {
                    locked.setState(ReviewItemState.WITHHELD);
                    locked.setReason(decision.reason());
                    locked.setAttemptedAt(clock.instant());
                }
            }
        }
        return Set.copyOf(reused);
    }

    /**
     * The current result that a requested review keeps: the candidate a scan would reuse, whose own capture read the same
     * sources. Only a person's request asks for this. An opened or ready occasion measures again by decision.
     */
    private Optional<ReuseCandidateQuery.ReusableResult> reusable(
            long workspaceId, Review review, ReviewCaptureDecision decision, long itemId) {
        if (review.getKind() != ReviewKind.ITEM
                || review.getOrigin() != ReviewOrigin.REQUEST
                || decision.status() != ReviewPracticeStatus.READY) return Optional.empty();
        return reuseCandidates.findWithCapture(
                workspaceId, new ReviewAssignmentId(review.getId(), decision.practiceId(), itemId));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> reusedPractices(long workspaceId, UUID reviewId, UUID jobId) {
        return items
                .findByWorkspaceIdAndReviewIdAndJobIdOrderByPracticeIdAscItemIdAsc(workspaceId, reviewId, jobId)
                .stream()
                .filter(member -> member.getState() == ReviewItemState.REUSED)
                .map(ReviewItem::getPracticeId)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    @Transactional
    public void withhold(long workspaceId, ReviewAssignmentId assignment, CandidateRefusal reason, String message) {
        recordWithheld(workspaceId, assignment, reason.wireValue(), message, null);
    }

    @Override
    @Transactional
    public void fail(
            long workspaceId, ReviewAssignmentId assignment, UUID jobId, ReviewStopReason reason, String message) {
        recordWithheld(workspaceId, assignment, reason.name(), message, jobId);
    }

    private void recordWithheld(
            long workspaceId, ReviewAssignmentId id, String reason, String message, @Nullable UUID failedBy) {
        boolean terminalFailure = failedBy != null;
        var review = reviews.lockByIdAndWorkspaceId(id.reviewId(), workspaceId).orElseThrow();
        if (review.getStatus().isTerminal() || (!terminalFailure && review.isCancelRequested()))
            throw new IllegalStateException("This review no longer accepts withholding.");
        if (message.length() > 1000)
            throw new IllegalArgumentException("Use a withholding detail of at most 1000 characters.");
        var assignment = practices.lockAssignments(workspaceId, id.reviewId()).stream()
                .filter(p -> p.getPracticeId() == id.practiceId())
                .findFirst()
                .orElseThrow();
        var member = items.lockAssignment(workspaceId, id.reviewId(), id.practiceId(), id.itemId())
                .orElseThrow();
        if (member.getState() != ReviewItemState.LISTED
                && !(terminalFailure && member.getState() == ReviewItemState.STAGED)) return;
        // A practice is staged while its attempt runs (ADR 0063), so the bound invocation may end one as soon as it
        // fails, and after the attempt ends until its processing settles.
        if (failedBy != null
                && (!failedBy.equals(member.getJobId())
                        || !(runtime.activeJobIds(workspaceId, id.reviewId()).contains(failedBy)
                                || runtime.unsettledJobIds(workspaceId, id.reviewId())
                                        .contains(failedBy))))
            throw new IllegalStateException("Only the bound invocation can record an execution failure.");
        member.withhold(reason, message, clock.instant());
        if (member.getStagedAttempt() != null) {
            member.setStagedAttempt(null);
            payloads.write(workspaceId, id.reviewId(), id.practiceId(), id.itemId(), null);
        }
        if (review.getKind() == ReviewKind.ITEM) {
            assignment.setStatus(ReviewPracticeStatus.WITHHELD);
            assignment.setReason(reason);
        }
    }
}
