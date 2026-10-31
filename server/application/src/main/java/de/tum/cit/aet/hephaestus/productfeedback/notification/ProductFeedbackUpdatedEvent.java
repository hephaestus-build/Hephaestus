package de.tum.cit.aet.hephaestus.productfeedback.notification;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Something happened to the GitHub issue of a person's own report. Published in the transaction that records it, so
 * the reporter's email commits with the change.
 *
 * @param issueUrl the public issue the email links to; null for a private issue, whose address names the private
 *     repository
 * @param detail the release for {@link Kind#INCLUDED_IN_RELEASE}, the original issue's URL for {@link Kind#DUPLICATE},
 *     or null
 */
public record ProductFeedbackUpdatedEvent(
        UUID feedbackId,
        long accountId,
        Kind kind,
        @Nullable String issueUrl,
        boolean publicIssue,
        @Nullable String detail,
        Instant at) {

    public enum Kind {
        /** The report is now an issue on GitHub. */
        FILED,
        /** A maintainer published a private report as a public issue. */
        PUBLISHED,
        /** Someone other than the app commented on the public issue. */
        COMMENTED,
        /** The issue was closed as completed. */
        CLOSED_COMPLETED,
        /** The issue was closed as not planned. */
        CLOSED_NOT_PLANNED,
        /** The issue was closed as a duplicate; Hephaestus now follows the original. */
        DUPLICATE,
        REOPENED,
        /** The change that closed the issue is in a published release. */
        INCLUDED_IN_RELEASE,
        /** The person's public issue moved to the private repository. */
        MADE_PRIVATE
    }
}
