package de.tum.cit.aet.hephaestus.practices.feedback.approval.dto;

import de.tum.cit.aet.hephaestus.practices.feedback.approval.FeedbackApproval;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.FeedbackApprovalDecision;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.FeedbackRejectionReason;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record FeedbackApprovalDTO(
        UUID feedbackId,
        FeedbackApprovalDecision decision,
        FeedbackRejectionReason rejectionReason,
        @Nullable String rejectionNote,
        @Nullable Long actorAccountId,
        Instant decidedAt) {
    public static FeedbackApprovalDTO from(FeedbackApproval approval) {
        return new FeedbackApprovalDTO(
                approval.getFeedbackId(),
                approval.getDecision(),
                approval.getRejectionReason(),
                approval.getRejectionNote(),
                approval.getActorAccountId(),
                approval.getDecidedAt());
    }
}
