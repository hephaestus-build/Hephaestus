package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAttemptGuard;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class ReviewInvocationFence {
    private final ReviewRepository reviews;
    private final ReviewAttemptGuard ownership;
    private final ReviewRuntimeQuery runtime;

    public Review lock(long workspaceId, UUID reviewId, UUID jobId, int attempt) {
        var review = reviews.lockByIdAndWorkspaceId(reviewId, workspaceId).orElseThrow();
        ownership.requireOwner(workspaceId, jobId, attempt);
        var job = runtime.runtime(workspaceId, reviewId).orElseThrow().jobs().stream()
                .filter(j -> j.id().equals(jobId) && j.attempt() == attempt)
                .findFirst()
                .orElseThrow(() -> conflict("The attempt belongs to another review."));
        if (review.getStatus() != ReviewStatus.RUNNING
                || job.status() != ReviewJobStatus.RUNNING
                || review.isCancelRequested()) throw conflict("This attempt can no longer write product proof.");
        return review;
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
