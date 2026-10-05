package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.DiffNote;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.Readback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatch;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchDestination;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Which copies on the reviewed work belong to one sealed dispatch, read once from the frozen row: the marker they
 * carry, the key each note is posted under, and how an existing copy is verified. Never derived from current output
 * or settings.
 *
 * @param approvedFeedbackId the approved proposal, whose notes are keyed by their position; null for an automatic
 *     package, whose notes keep their source key
 * @param sealed whether the package recorded its own marker and its final inline text when it was persisted
 * @param unrecordedNotesUnsent whether a note with no receipt provably was never requested: true only while the
 *     dispatch records that no inline write began
 * @param reviewedRevision the commit an approved proposal was reviewed at, when it recorded one; otherwise the
 *     job's pinned commit anchors the notes
 */
record InlinePackageScope(
        String marker,
        @Nullable UUID approvedFeedbackId,
        boolean sealed,
        boolean unrecordedNotesUnsent,
        @Nullable String reviewedRevision) {

    /** The marker an automatic package shared before each package carried its own. */
    static final String SHARED_MARKER = "<!-- hephaestus-diff-note -->";

    static String automaticMarker(UUID jobId) {
        return "<!-- hephaestus-review-package:" + jobId + " -->";
    }

    static String approvedMarker(UUID feedbackId) {
        return "<!-- hephaestus-approved-package:" + feedbackId + " -->";
    }

    /** A stored marker that is not the one its own package derives is refused, never read as historical. */
    static InlinePackageScope of(
            FeedbackDispatch dispatch, @Nullable String storedMarker, @Nullable String reviewedRevision) {
        boolean approved = dispatch.getDestination() == FeedbackDispatchDestination.APPROVED_REVIEW_PACKAGE;
        UUID feedbackId = approved ? dispatch.approvedFeedbackId() : null;
        String own = feedbackId != null ? approvedMarker(feedbackId) : automaticMarker(dispatch.getAgentJobId());
        if (storedMarker != null && !storedMarker.equals(own)) {
            throw new JobDeliveryException(
                    "The sealed inline marker does not belong to its package: " + dispatch.getId());
        }
        String marker = storedMarker != null ? storedMarker : feedbackId != null ? own : SHARED_MARKER;
        return new InlinePackageScope(
                marker,
                feedbackId,
                storedMarker != null,
                Boolean.FALSE.equals(dispatch.getInlineWriteStarted()),
                reviewedRevision);
    }

    static InlinePackageScope recovered(
            FeedbackDispatch dispatch, @Nullable String storedMarker, FeedbackRepository feedbackRepository) {
        UUID feedbackId = dispatch.getDestination() == FeedbackDispatchDestination.APPROVED_REVIEW_PACKAGE
                ? dispatch.approvedFeedbackId()
                : null;
        String revision = feedbackId == null
                ? null
                : feedbackRepository
                        .findByIdAndWorkspaceId(feedbackId, dispatch.getWorkspaceId())
                        .orElseThrow(() -> new JobDeliveryException("The approved package has no proposal"))
                        .getReviewedRevision();
        return of(dispatch, storedMarker, revision);
    }

    /** The key a note is posted and acknowledged under. */
    @Nullable
    String keyFor(DiffNote note, int index) {
        return approvedFeedbackId != null ? "approved:" + approvedFeedbackId + ":" + index : note.deliveryKey();
    }

    /** Whether the inline text still needs the historical automatic disclosure appended at posting. */
    boolean appendsDisclosure() {
        return approvedFeedbackId == null && !sealed;
    }

    Readback readback() {
        if (sealed) return Readback.AUTHORED;
        return approvedFeedbackId != null ? Readback.PACKAGE : Readback.SHARED;
    }
}
