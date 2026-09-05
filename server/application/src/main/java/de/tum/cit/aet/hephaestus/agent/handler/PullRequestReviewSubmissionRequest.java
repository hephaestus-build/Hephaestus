package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.spi.JobSubmissionRequest;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Submission request for {@code PULL_REQUEST_REVIEW} jobs. Combines the async-safe
 * {@link ScmEventPayload.PullRequestData} snapshot with branch information not present on that DTO.
 *
 * @param observationOrigin which population this run's observations belong to; {@code null} defaults to
 *     the rule below. Explicit because the default cannot see a backfill: a campaign replays the signal
 *     the artifact's current state would have raised, so its request carries a trigger signal and would
 *     otherwise be filed as LIVE.
 */
public record PullRequestReviewSubmissionRequest(
        ScmEventPayload.PullRequestData pullRequest,
        String headRefName,
        String headRefOid,
        String baseRefName,
        @Nullable SignalName triggerSignal,
        @Nullable ObservationOrigin observationOrigin,
        @Nullable Long reviewId,
        @Nullable Long aboutUserId)
        implements JobSubmissionRequest {
    public PullRequestReviewSubmissionRequest {
        Objects.requireNonNull(pullRequest, "pullRequest must not be null");
        Objects.requireNonNull(pullRequest.repository(), "pullRequest.repository() must not be null");
        Objects.requireNonNull(headRefName, "headRefName must not be null");
        Objects.requireNonNull(headRefOid, "headRefOid must not be null");
        Objects.requireNonNull(baseRefName, "baseRefName must not be null");
        if (headRefName.isBlank()) {
            throw new IllegalArgumentException("headRefName must not be blank");
        }
        if (headRefOid.isBlank()) {
            throw new IllegalArgumentException("headRefOid must not be blank");
        }
        if (baseRefName.isBlank()) {
            throw new IllegalArgumentException("baseRefName must not be blank");
        }
        if (observationOrigin == null) {
            // A run with no lifecycle event behind it was asked for by a person, so its observations are a
            // self-selected sample (not a random draw from the work) and are recorded as such.
            observationOrigin = triggerSignal == null ? ObservationOrigin.MANUAL : ObservationOrigin.LIVE;
        }
    }

    /** For the event-driven and resubmission paths, which take the origin rule as it stands. */
    public PullRequestReviewSubmissionRequest(
            ScmEventPayload.PullRequestData pullRequest,
            String headRefName,
            String headRefOid,
            String baseRefName,
            @Nullable SignalName triggerSignal) {
        this(pullRequest, headRefName, headRefOid, baseRefName, triggerSignal, null, null, null);
    }

    public PullRequestReviewSubmissionRequest(
            ScmEventPayload.PullRequestData pullRequest,
            String headRefName,
            String headRefOid,
            String baseRefName,
            @Nullable SignalName triggerSignal,
            @Nullable ObservationOrigin observationOrigin) {
        this(pullRequest, headRefName, headRefOid, baseRefName, triggerSignal, observationOrigin, null, null);
    }

    /** For callers with no signal behind the run; the job then runs the full focus-active practice set. */
    public PullRequestReviewSubmissionRequest(
            ScmEventPayload.PullRequestData pullRequest, String headRefName, String headRefOid, String baseRefName) {
        this(pullRequest, headRefName, headRefOid, baseRefName, null, null, null, null);
    }

    /** The same request filed under a named population; {@code null} falls back to the origin rule above. */
    public PullRequestReviewSubmissionRequest withOrigin(@Nullable ObservationOrigin origin) {
        return new PullRequestReviewSubmissionRequest(
                pullRequest, headRefName, headRefOid, baseRefName, triggerSignal, origin, reviewId, aboutUserId);
    }

    public static PullRequestReviewSubmissionRequest forSubmittedReview(
            ScmEventPayload.PullRequestData pullRequest,
            String headRefName,
            String headRefOid,
            String baseRefName,
            SignalName triggerSignal,
            ScmEventPayload.ReviewData review) {
        return new PullRequestReviewSubmissionRequest(
                pullRequest,
                headRefName,
                headRefOid,
                baseRefName,
                triggerSignal,
                null,
                review.id(),
                Objects.requireNonNull(review.authorId(), "submitted review must have an author"));
    }
}
