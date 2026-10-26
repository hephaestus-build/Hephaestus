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

    void capture(long workspaceId, UUID reviewId, UUID jobId, int attempt, List<ReviewCaptureDecision> decisions);

    void withhold(long workspaceId, ReviewAssignmentId assignment, CandidateRefusal reason, String message);

    void fail(long workspaceId, ReviewAssignmentId assignment, ReviewStopReason reason, String message);
}
