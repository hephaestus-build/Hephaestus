package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.UUID;

/** The checked quiet-correction path; it never creates a provider object. */
public interface FeedbackRepair {
    FeedbackRepairStatus readback(long workspaceId, UUID reviewId, UUID targetId);

    FeedbackRepairStatus readbackStatus(long workspaceId, UUID reviewId, UUID targetId);

    /** The latest revision's delivery facts, without the repeats that {@link #statusWithRepeats} reads. */
    FeedbackRepairStatus status(long workspaceId, UUID reviewId);

    /** The status as the composer reads it: with the repeats that its readback flags. */
    FeedbackRepairStatus statusWithRepeats(long workspaceId, UUID reviewId);

    FeedbackDraft check(FeedbackDraft proposal, UUID targetId, String draftId);

    PreparedPackage prepare(FeedbackDraft proposal, UUID targetId, String draftId);

    boolean pending(long workspaceId, UUID reviewId);

    void stop(long workspaceId, UUID reviewId, String reason);
}
