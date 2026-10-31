package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class ReviewJobAssignments {
    private final ReviewSnapshotQuery reviews;
    private final ReviewAssignmentQuery assignments;
    private final ReviewAssignmentStore membership;

    public ReviewSnapshot review(ReviewJob job) {
        return reviews.find(job.getWorkspaceId(), job.getReviewId())
                .filter(review -> review.id().equals(job.getReviewId()) && review.workspaceId() == job.getWorkspaceId())
                .orElseThrow(() -> invalid("The review does not belong to this job's workspace"));
    }

    public List<ReviewAssignmentSnapshot> load(ReviewJob job) {
        return load(job.getWorkspaceId(), job.getReviewId(), job.getInput());
    }

    public List<ReviewAssignmentSnapshot> load(long workspaceId, UUID reviewId, ReviewJobInput input) {
        ReviewSnapshot review = reviews.find(workspaceId, reviewId)
                .orElseThrow(() -> invalid("The review is unavailable in this workspace"));
        return input.assignments().stream()
                .map(id -> {
                    var assignment = assignments
                            .find(workspaceId, id)
                            .orElseThrow(() -> invalid("A frozen review assignment is unavailable"));
                    if (!assignment.id().equals(id)
                            || !id.reviewId().equals(review.id())
                            || id.itemId() != input.itemId()
                            || assignment.item().workspaceId() != workspaceId
                            || assignment.item().itemId() != id.itemId()
                            || assignment.practice().practiceId() != id.practiceId()
                            || assignment.version() == null
                            || assignment.version().value().isBlank()
                            || assignment.practiceSlug().isBlank()) {
                        throw invalid("The frozen assignment does not match the job input");
                    }
                    boolean pinned = review.practices().stream()
                            .anyMatch(practice -> practice.practiceId()
                                            == assignment.practice().practiceId()
                                    && practice.practiceVersionId()
                                            == assignment.practice().practiceVersionId()
                                    && practice.reviewDigest()
                                            .equals(assignment.practice().reviewDigest())
                                    && practice.method()
                                            .equals(assignment.practice().method()));
                    if (!pinned)
                        throw invalid("The assignment's practice version or method is not pinned by the review");
                    return assignment;
                })
                .toList();
    }

    public boolean publicReviewed(ReviewJob job) {
        return reviews.publicReviewedAt(job.getWorkspaceId(), job.getReviewId()).isPresent();
    }

    /** Practices refused at capture do not become missing result submissions. */
    public List<ReviewAssignmentSnapshot> admissible(ReviewJob job) {
        var captured = job.getCapture();
        var pinned = load(job);
        if (captured == null || !captured.path("readiness").path("decisions").isArray()) return pinned;
        var ready = new HashSet<String>();
        for (var decision : captured.path("readiness").path("decisions"))
            if (decision.path("ready").asBoolean(false)
                    && !captured.path("readiness")
                            .path("applicability")
                            .path(decision.path("practiceSlug").asString())
                            .path("absent")
                            .asBoolean(false))
                ready.add(decision.path("practiceSlug").asString());
        return pinned.stream()
                .filter(assignment -> ready.contains(assignment.practiceSlug()))
                .toList();
    }

    public boolean hasStaged(ReviewJob job) {
        return membership.hasStaged(job.getWorkspaceId(), job.getReviewId(), job.getId());
    }

    public boolean readyToSeal(ReviewJob job) {
        return hasStaged(job) && open(job).isEmpty();
    }

    public List<ReviewAssignmentSnapshot> open(ReviewJob job) {
        var ended = membership.endedPractices(job.getWorkspaceId(), job.getReviewId(), job.getId());
        var captured = job.getCapture();
        return load(job).stream()
                .filter(assignment -> !ended.contains(assignment.practice().practiceId()))
                .filter(assignment -> captured == null
                        || !captured.path("readiness")
                                .path("applicability")
                                .path(assignment.practiceSlug())
                                .path("absent")
                                .asBoolean(false))
                .toList();
    }

    private static JobPreparationException invalid(String message) {
        return new JobPreparationException(JobPreparationException.Reason.INPUT_CONTRACT_INVALID, message);
    }
}
