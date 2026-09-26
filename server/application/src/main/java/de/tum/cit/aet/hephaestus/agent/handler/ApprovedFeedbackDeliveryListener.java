package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.config.FeedbackLaneExecutor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyStage;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.ApprovedFeedbackReadyEvent;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.FeedbackApprovalDigest;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.FeedbackApprovalEligibility;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.FeedbackApprovalRepository;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
class ApprovedFeedbackDeliveryListener {

    private static final Logger log = LoggerFactory.getLogger(ApprovedFeedbackDeliveryListener.class);
    private final FeedbackRepository feedbackRepository;
    private final FeedbackApprovalRepository approvalRepository;
    private final AgentJobRepository agentJobRepository;
    private final PracticeFeedbackDeliveryPolicy deliveryPolicy;
    private final PracticeFeedbackDispatchService dispatchService;
    private final FeedbackApprovalEligibility approvalEligibility;
    private final FeedbackLedgerRecorder feedbackLedgerRecorder;

    // Off the approving request, so it does not wait on provider HTTP. NOT_SUPPORTED matters when the
    // executor runs the task on the committing thread, as the synchronous test executor does: the
    // finished approval is still bound there, and a write that joined it would never commit.
    @Async(FeedbackLaneExecutor.BEAN_NAME)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onApproved(ApprovedFeedbackReadyEvent event) {
        deliver(event);
    }

    void deliver(ApprovedFeedbackReadyEvent event) {
        Feedback feedback = feedbackRepository
                .findByIdAndWorkspaceId(event.feedbackId(), event.workspaceId())
                .orElse(null);
        if (feedback == null
                || (feedback.getDeliveryState() != FeedbackDeliveryState.PREPARED
                        && feedback.getDeliveryState() != FeedbackDeliveryState.PARTIALLY_DELIVERED)) return;
        var approval = approvalRepository
                .findByFeedbackIdAndWorkspaceId(feedback.getId(), event.workspaceId())
                .orElse(null);
        if (approval == null || !approval.getContentDigest().equals(FeedbackApprovalDigest.of(feedback))) {
            log.error("Approved proposal content no longer matches its approval: feedbackId={}", feedback.getId());
            stop(feedback, event.workspaceId(), FeedbackSuppressionReason.APPROVAL_STALE);
            return;
        }
        if (!approvalEligibility.isEligible(event.workspaceId(), feedback.getId())) {
            stop(feedback, event.workspaceId(), FeedbackSuppressionReason.APPROVAL_NO_LONGER_ELIGIBLE);
            return;
        }
        AgentJob job = agentJobRepository
                .findByIdAndWorkspaceId(feedback.getAgentJobId(), event.workspaceId())
                .orElse(null);
        if (job == null || feedback.getBody() == null || feedback.getBody().isBlank()) return;

        PracticeFeedbackDeliveryPolicy.Decision<?> policy;
        if (ArtifactKinds.ISSUE.equals(feedback.getArtifactKind())) {
            policy = deliveryPolicy.evaluateIssue(
                    job, DeliveryPolicyStage.APPROVED, feedback.getId(), feedback.getProposedPracticeSlugs());
        } else if (ArtifactKinds.PULL_REQUEST.equals(feedback.getArtifactKind())) {
            policy = deliveryPolicy.evaluatePullRequest(
                    job, DeliveryPolicyStage.APPROVED, feedback.getId(), feedback.getProposedPracticeSlugs());
        } else {
            log.error(
                    "Approved proposal has no supported artifact kind: feedbackId={}, artifactKind={}",
                    feedback.getId(),
                    feedback.getArtifactKind());
            return;
        }
        if (!policy.allowed()) {
            if (policy.suppressionReason() != null) {
                stop(feedback, event.workspaceId(), policy.refusal());
            }
            return;
        }
        if (policy.target() instanceof PullRequest pullRequest
                && feedback.getReviewedRevision() != null
                && !feedback.getReviewedRevision().equals(pullRequest.getHeadRefOid())) {
            stop(feedback, event.workspaceId(), FeedbackSuppressionReason.APPROVAL_STALE);
            return;
        }

        PracticeFeedbackDispatchService.Result result = dispatchService.dispatchApproved(job, feedback);
        if (result.status() == PracticeFeedbackDispatchService.Result.Status.SUPPRESSED) {
            FeedbackSuppressionReason reason = result.refusal();
            dispatchService.projectApproved(feedback, () -> {
                feedbackLedgerRecorder.recordApprovedPlacements(
                        feedback, result.externalRef(), result.deliveredSignals());
                if (result.externalRef() == null) {
                    stop(feedback, event.workspaceId(), reason);
                } else {
                    feedbackRepository.markApprovedPartiallyDelivered(
                            event.workspaceId(), feedback.getId(), reason.name());
                }
            });
            return;
        }
        if (result.status() == PracticeFeedbackDispatchService.Result.Status.SENT) {
            dispatchService.projectApproved(feedback, () -> {
                feedbackLedgerRecorder.recordApprovedPlacements(
                        feedback, result.externalRef(), result.deliveredSignals());
                feedbackRepository.markApprovedDelivered(event.workspaceId(), feedback.getId());
            });
            return;
        }
        if (result.status() == PracticeFeedbackDispatchService.Result.Status.FAILED) {
            dispatchService.projectApproved(feedback, () -> {
                feedbackLedgerRecorder.recordApprovedPlacements(
                        feedback, result.externalRef(), result.deliveredSignals());
                if (result.externalRef() == null) {
                    feedbackRepository.markApprovedFailed(event.workspaceId(), feedback.getId());
                } else {
                    feedbackRepository.markApprovedPartiallyFailed(event.workspaceId(), feedback.getId());
                }
            });
            return;
        }
        if (result.externalRef() != null) {
            feedbackRepository.markApprovedPartiallyDelivered(event.workspaceId(), feedback.getId(), null);
        } else {
            log.warn("Approved proposal deferred for dispatch reconciliation: feedbackId={}", feedback.getId());
        }
    }

    private void stop(Feedback feedback, Long workspaceId, FeedbackSuppressionReason reason) {
        if (feedback.getDeliveryState() == FeedbackDeliveryState.PARTIALLY_DELIVERED) {
            feedbackRepository.markApprovedPartiallyDelivered(workspaceId, feedback.getId(), reason.name());
        } else {
            feedbackRepository.markApprovedSuppressed(workspaceId, feedback.getId(), reason.name());
        }
    }
}
