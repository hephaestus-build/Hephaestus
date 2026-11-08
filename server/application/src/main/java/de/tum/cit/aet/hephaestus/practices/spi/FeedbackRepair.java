package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.UUID;

/** The checked quiet-correction path; it never creates a provider object. */
public interface FeedbackRepair {
    FeedbackRepairStatus readback(long workspaceId, UUID reviewId, UUID targetId);

    FeedbackRepairStatus readbackStatus(long workspaceId, UUID reviewId, UUID targetId);

    FeedbackRepairStatus status(long workspaceId, UUID reviewId);

    FeedbackDraft check(FeedbackDraft proposal, UUID targetId, String draftId);

    PreparedPackage prepare(FeedbackDraft proposal, UUID targetId, String draftId);

    boolean pending(long workspaceId, UUID reviewId);

    void stop(long workspaceId, UUID reviewId, String reason);
}
