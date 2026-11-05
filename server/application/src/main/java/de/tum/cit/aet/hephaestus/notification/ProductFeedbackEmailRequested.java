package de.tum.cit.aet.hephaestus.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One instance admin's email about product feedback.
 *
 * @param feedbackId the report, or for {@link Kind#CLEANUP} the first cleanup record; the name stays because stored
 *     event publications carry it
 * @param cleanupIds for {@link Kind#CLEANUP}, every cleanup record that the email lists; absent for the other kinds
 */
public record ProductFeedbackEmailRequested(
        UUID feedbackId,
        long accountId,
        Instant requestedAt,
        Instant expiresAt,
        Kind kind,
        @Nullable List<UUID> cleanupIds) {

    /** A stored publication reads its kind by name, so a constant is never renamed in place. */
    public enum Kind {
        BUG,
        IDEA,
        FEEDBACK,
        /** An erased report's GitHub cleanup waits for a maintainer. */
        CLEANUP
    }
}
