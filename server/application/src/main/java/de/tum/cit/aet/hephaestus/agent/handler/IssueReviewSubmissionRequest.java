package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.spi.JobSubmissionRequest;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record IssueReviewSubmissionRequest(
        long issueId,
        int issueNumber,
        long repositoryId,
        String repositoryFullName,
        String title,
        String body,
        String state,
        @Nullable String url,
        @Nullable Instant updatedAt,
        @Nullable SignalName triggerSignal,
        @Nullable ObservationOrigin observationOrigin,
        @Nullable Long actorUserId,
        @Nullable UUID reviewSnapshotId)
        implements JobSubmissionRequest {
    public IssueReviewSubmissionRequest {
        Objects.requireNonNull(repositoryFullName, "repositoryFullName must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(state, "state must not be null");
        if (repositoryFullName.isBlank()) {
            throw new IllegalArgumentException("repositoryFullName must not be blank");
        }
        if (issueNumber <= 0) {
            throw new IllegalArgumentException("issueNumber must be positive, got " + issueNumber);
        }
        if (issueId <= 0) {
            throw new IllegalArgumentException("issueId must be positive, got " + issueId);
        }
        if (repositoryId <= 0) {
            throw new IllegalArgumentException("repositoryId must be positive, got " + repositoryId);
        }
        if (observationOrigin == null) {
            // Same rule as a pull-request review: no signal behind it means a person asked.
            observationOrigin = triggerSignal == null ? ObservationOrigin.MANUAL : ObservationOrigin.LIVE;
        }
    }

    /** Constructor for the event-driven and resubmission paths, which take the origin rule as it stands. */
    public IssueReviewSubmissionRequest(
            long issueId,
            int issueNumber,
            long repositoryId,
            String repositoryFullName,
            String title,
            String body,
            String state,
            @Nullable String url,
            @Nullable Instant updatedAt,
            @Nullable SignalName triggerSignal) {
        this(
                issueId,
                issueNumber,
                repositoryId,
                repositoryFullName,
                title,
                body,
                state,
                url,
                updatedAt,
                triggerSignal,
                null,
                null,
                null);
    }

    public IssueReviewSubmissionRequest(
            long issueId,
            int issueNumber,
            long repositoryId,
            String repositoryFullName,
            String title,
            String body,
            String state,
            @Nullable String url,
            @Nullable Instant updatedAt,
            @Nullable SignalName triggerSignal,
            @Nullable ObservationOrigin observationOrigin) {
        this(
                issueId,
                issueNumber,
                repositoryId,
                repositoryFullName,
                title,
                body,
                state,
                url,
                updatedAt,
                triggerSignal,
                observationOrigin,
                null,
                null);
    }
}
