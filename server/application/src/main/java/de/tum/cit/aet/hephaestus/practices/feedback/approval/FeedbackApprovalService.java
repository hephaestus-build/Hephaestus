package de.tum.cit.aet.hephaestus.practices.feedback.approval;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.dto.DecideFeedbackProposalRequestDTO;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class FeedbackApprovalService {

    private final FeedbackRepository feedbackRepository;
    private final FeedbackApprovalRepository approvalRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final FeedbackApprovalEligibility eligibility;

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public FeedbackApproval decide(
            Long workspaceId, UUID feedbackId, Long actorAccountId, DecideFeedbackProposalRequestDTO request) {
        Feedback feedback = feedbackRepository
                .findByIdAndWorkspaceId(feedbackId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Feedback", feedbackId.toString()));
        validate(request);
        if (feedback.getChannel() != FeedbackChannel.IN_CONTEXT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only in-context feedback can be approved");
        }
        if (request.decision() == FeedbackApprovalDecision.APPROVED) {
            FeedbackSuppressionReason brake = eligibility.brakeOnDelivery(workspaceId);
            if (brake != null) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        brake == FeedbackSuppressionReason.INSTANCE_SILENCED
                                ? "Silent Mode is engaged for this instance, so approving would not send anything"
                                : "Sending is paused for this workspace, so approving would not send anything");
            }
        }
        if (request.decision() == FeedbackApprovalDecision.APPROVED
                && !eligibility.isEligible(workspaceId, feedbackId)) {
            feedbackRepository.suppressProposal(
                    workspaceId, feedbackId, FeedbackSuppressionReason.APPROVAL_NO_LONGER_ELIGIBLE.name());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This proposal is no longer eligible for approval");
        }
        FeedbackApproval existing = approvalRepository
                .findByFeedbackIdAndWorkspaceId(feedbackId, workspaceId)
                .orElse(null);
        if (existing != null) {
            if (existing.getDecision() != request.decision()
                    || existing.getRejectionReason() != request.rejectionReason()
                    || !Objects.equals(existing.getRejectionNote(), normalizedNote(request.rejectionNote()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This proposal has already been decided");
            }
            if (existing.getDecision() == FeedbackApprovalDecision.APPROVED) {
                eventPublisher.publishEvent(new ApprovedFeedbackReadyEvent(workspaceId, feedbackId));
            }
            return existing;
        }

        FeedbackDeliveryState target = request.decision() == FeedbackApprovalDecision.APPROVED
                ? FeedbackDeliveryState.PREPARED
                : FeedbackDeliveryState.DISCARDED;
        if (feedbackRepository.decideProposal(workspaceId, feedbackId, target.name()) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This proposal has already been decided");
        }

        FeedbackApproval approval = approvalRepository.save(FeedbackApproval.builder()
                .feedbackId(feedbackId)
                .workspaceId(workspaceId)
                .actorAccountId(actorAccountId)
                .decision(request.decision())
                .rejectionReason(request.rejectionReason())
                .rejectionNote(normalizedNote(request.rejectionNote()))
                .contentDigest(FeedbackApprovalDigest.of(feedback))
                .build());
        if (request.decision() == FeedbackApprovalDecision.APPROVED) {
            eventPublisher.publishEvent(new ApprovedFeedbackReadyEvent(workspaceId, feedbackId));
        }
        return approval;
    }

    @Transactional(readOnly = true)
    public FeedbackApproval get(Long workspaceId, UUID feedbackId) {
        return approvalRepository
                .findByFeedbackIdAndWorkspaceId(feedbackId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Feedback approval", feedbackId.toString()));
    }

    private static void validate(DecideFeedbackProposalRequestDTO request) {
        if (request.decision() == FeedbackApprovalDecision.REJECTED && request.rejectionReason() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A rejection needs a reason");
        }
        if (request.decision() == FeedbackApprovalDecision.APPROVED && request.rejectionReason() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An approval cannot have a rejection reason");
        }
        if (request.decision() == FeedbackApprovalDecision.APPROVED
                && normalizedNote(request.rejectionNote()) != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An approval cannot have a rejection note");
        }
    }

    private static @Nullable String normalizedNote(@Nullable String note) {
        return note == null || note.isBlank() ? null : note.trim();
    }
}
