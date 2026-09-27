package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The vendor pipe that posts a review's summary — every integration declaring
 * {@link Capability#FEEDBACK_DELIVERY} implements it, alongside the capability-gated
 * {@link InlineFeedbackChannel} and {@link ApprovalChannel}.
 *
 * <p>Distinct from {@code practices.feedback.FeedbackChannel}, which names where feedback landed; this
 * one does the landing.
 */
public interface SummaryChannel {
    IntegrationKind kind();

    SummaryHandle postSummary(FeedbackTarget target, FeedbackContent content);

    /**
     * Edits an already-posted summary <em>in place</em> (ADR 0021 re-review UX) instead of re-posting, so a
     * re-reviewed PR/MR keeps one evolving thread. {@code externalId} is the handle a prior
     * {@link #postSummary} returned.
     */
    default UpdateOutcome updateSummary(FeedbackTarget target, String externalId, FeedbackContent content) {
        return UpdateOutcome.unsupported();
    }

    /**
     * Search the target's existing comments for one carrying {@code marker}, so a delivery-recovery retry
     * after a crash can record the already-posted comment instead of posting a duplicate.
     *
     * <p>Only {@code ABSENT} licenses posting: a channel that cannot distinguish "searched everything,
     * nothing matched" from "could not search" must answer {@code UNKNOWN} (the default), and the caller
     * must then leave the delivery {@code PENDING} rather than risk a second summary.
     */
    default ExistingSummaryLookup findExistingSummary(FeedbackTarget target, String marker) {
        return ExistingSummaryLookup.unknown();
    }

    /**
     * Format the vendor's external identifier for a pull request / merge request: GitHub uses
     * {@code repoFullName#prNumber}; GitLab uses {@code repoFullName!prNumber}.
     *
     * @throws IllegalArgumentException if {@code repoFullName} is not well-formed for the
     *     vendor (e.g. GitHub's two-segment {@code owner/repo} requirement).
     */
    String formatPullRequestSubjectId(String repoFullName, int prNumber);

    /**
     * Format the vendor's external identifier for an issue: both GitHub and GitLab address issues as
     * {@code repoFullName#issueNumber}. The GitLab channel routes a {@code #}-suffixed subject to the
     * issue note path (vs {@code !} for a merge request); a vendor with a different scheme overrides.
     */
    default String formatIssueSubjectId(String repoFullName, int issueNumber) {
        if (repoFullName == null || repoFullName.isBlank()) {
            throw new IllegalArgumentException("repoFullName is required");
        }
        return repoFullName + "#" + issueNumber;
    }

    record FeedbackTarget(
            IntegrationRef ref,
            String subjectExternalId,
            @Nullable String resourceUrl) {}

    record FeedbackContent(String body, String marker) {
        public String externalBody() {
            return marker == null || marker.isBlank()
                    ? body
                    : body.replace(marker, "").stripTrailing() + "\n\n" + marker;
        }
    }

    /** Vendor-side post identifier recorded on {@code FeedbackPlacement.postedCommentRef} for edit-in-place (ADR 0021). */
    record SummaryHandle(String externalId, @Nullable String url) {
        public SummaryHandle(String externalId) {
            this(externalId, null);
        }
    }

    record ExistingSummaryLookup(Kind kind, @Nullable SummaryHandle handle) {
        public enum Kind {
            FOUND,
            ABSENT,
            UNKNOWN,
        }

        public static ExistingSummaryLookup found(SummaryHandle handle) {
            Objects.requireNonNull(handle, "FOUND outcome requires a SummaryHandle");
            return new ExistingSummaryLookup(Kind.FOUND, handle);
        }

        public static ExistingSummaryLookup absent() {
            return new ExistingSummaryLookup(Kind.ABSENT, null);
        }

        public static ExistingSummaryLookup unknown() {
            return new ExistingSummaryLookup(Kind.UNKNOWN, null);
        }
    }

    /**
     * The outcome of an {@link #updateSummary} attempt. {@code TRANSIENT} is the load-bearing case: the caller
     * must NOT create-fallback on it (that double-posts), only on {@code GONE}/{@code UNSUPPORTED}.
     */
    record UpdateOutcome(
            Kind kind,
            @Nullable SummaryHandle handle,
            @Nullable String reason) {
        public enum Kind {
            EDITED,
            GONE,
            TRANSIENT,
            UNSUPPORTED,
        }

        public static UpdateOutcome edited(SummaryHandle handle) {
            // EDITED guarantees a usable handle (the caller dereferences handle().externalId()); a null
            // handle / blank id is a contract bug in an impl — fail at the boundary, not as a downstream NPE.
            Objects.requireNonNull(handle, "EDITED outcome requires a SummaryHandle");
            if (handle.externalId() == null || handle.externalId().isBlank()) {
                throw new IllegalArgumentException("EDITED outcome requires a non-blank externalId");
            }
            return new UpdateOutcome(Kind.EDITED, handle, null);
        }

        /** The prior comment is confirmed gone (a human deleted it) — the caller should re-post. */
        public static UpdateOutcome gone(String reason) {
            return new UpdateOutcome(Kind.GONE, null, reason);
        }

        /** A recoverable failure (rate limit, network, unknown vendor error) — keep the prior summary, do not re-post. */
        public static UpdateOutcome transientFailure(String reason) {
            return new UpdateOutcome(Kind.TRANSIENT, null, reason);
        }

        /** This channel cannot edit in place (append-only) — the caller should re-post. */
        public static UpdateOutcome unsupported() {
            return new UpdateOutcome(Kind.UNSUPPORTED, null, null);
        }
    }
}
