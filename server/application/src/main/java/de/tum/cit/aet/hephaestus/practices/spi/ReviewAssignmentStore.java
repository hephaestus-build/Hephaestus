package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.integration.core.spi.CandidateRefusal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ReviewAssignmentStore {
    boolean publicReviewed(long workspaceId, UUID reviewId, UUID jobId, int attempt, Instant acceptedAt);

    void bind(long workspaceId, UUID reviewId, UUID jobId, List<ReviewAssignmentId> assignments);

    /** The practices of the job whose membership an earlier attempt of the job already ended. */
    Set<Long> endedPractices(long workspaceId, UUID reviewId, UUID jobId);

    /** Whether an invocation retains verified practices that still need its seal. */
    boolean hasStaged(long workspaceId, UUID reviewId, UUID jobId);

    /**
     * Records what capture decided. In a requested item review, a ready practice whose current result read the same
     * sources keeps that result instead of running.
     *
     * @return the practices that reuse a result and must not run
     */
    Set<Long> capture(long workspaceId, UUID reviewId, UUID jobId, int attempt, List<ReviewCaptureDecision> decisions);

    /** The practices of this job that keep an earlier result: they do not run, and no later attempt runs them. */
    Set<Long> reusedPractices(long workspaceId, UUID reviewId, UUID jobId);

    void withhold(long workspaceId, ReviewAssignmentId assignment, CandidateRefusal reason, String message);

    void fail(long workspaceId, ReviewAssignmentId assignment, ReviewStopReason reason, String message);
}
