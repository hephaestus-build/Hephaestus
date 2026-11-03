package de.tum.cit.aet.hephaestus.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * One instance admin's email about product feedback.
 *
 * @param feedbackId the report, or for {@link Kind#CLEANUP} the cleanup record; the name stays because stored event
 *     publications carry it
 */
public record ProductFeedbackEmailRequested(
        UUID feedbackId, long accountId, Instant requestedAt, Instant expiresAt, Kind kind) {

    /** A stored publication reads its kind by name, so a constant is never renamed in place. */
    public enum Kind {
        BUG,
        IDEA,
        FEEDBACK,
        /** An erased report's GitHub cleanup waits for a maintainer. */
        CLEANUP
    }
}
