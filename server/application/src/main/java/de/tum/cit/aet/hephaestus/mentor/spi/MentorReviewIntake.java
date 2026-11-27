package de.tum.cit.aet.hephaestus.mentor.spi;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes the one {@code REVIEW_SEALED} mailbox event of a review for its reviewed account, in the seal's transaction
 * (ADR 0160). It holds the account and its membership until the seal commits, and it calls no provider.
 */
public interface MentorReviewIntake {
    /** MANDATORY: in the seal's transaction, which already holds the review's write lock. */
    Outcome admit(SealedReview sealed);

    /** The IDs that the event carries: no text. */
    record SealedReview(
            long workspaceId, UUID reviewId, UUID jobId, long accountId, String admissionDigest, Instant sealedAt) {}

    sealed interface Outcome {
        /** The event is written, or this same seal wrote it before. */
        record Admitted(UUID eventId) implements Outcome {}

        /** The account cannot own the event. Nothing is written, and no owner is bound. */
        record Refused(Refusal reason) implements Outcome {}
    }

    enum Refusal {
        /** The write fence refused: a processing restriction, an erasure, or an account that is blocked or deleting. */
        OWNER_REFUSED,
        /** The account has not signed in yet. */
        AUTHOR_INACTIVE,
        /** The account is no current, visible member of the workspace. */
        AUTHOR_NOT_MEMBER,
        /** Practice feedback delivery is off, or the account made no AI choice, or chose No AI. */
        AUTHOR_NOT_CONSENTING,
        /** The review's event exists for another owner, job or admission. */
        EVENT_MISMATCH
    }
}
