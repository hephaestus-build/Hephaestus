package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.DeliveryContent;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ExistingDeliveryLookup;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobService;
import de.tum.cit.aet.hephaestus.agent.job.DeliveryStatus;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.DeliveredSignal;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.Disposition;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyStage;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatch;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class FeedbackDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackDeliveryService.class);

    private final PullRequestCommentPoster commentPoster;
    private final PracticeFeedbackDeliveryPolicy deliveryPolicy;
    private final FeedbackLedgerRecorder feedbackLedgerRecorder;
    private final PracticeFeedbackCommentFormatter commentFormatter;
    private final PracticeFeedbackDispatchService dispatchService;
    private final AgentJobRepository agentJobRepository;

    FeedbackDeliveryService(
            PullRequestCommentPoster commentPoster,
            PracticeFeedbackDeliveryPolicy deliveryPolicy,
            FeedbackLedgerRecorder feedbackLedgerRecorder,
            PracticeFeedbackCommentFormatter commentFormatter,
            PracticeFeedbackDispatchService dispatchService,
            AgentJobRepository agentJobRepository) {
        this.commentPoster = commentPoster;
        this.deliveryPolicy = deliveryPolicy;
        this.feedbackLedgerRecorder = feedbackLedgerRecorder;
        this.commentFormatter = commentFormatter;
        this.dispatchService = dispatchService;
        this.agentJobRepository = agentJobRepository;
    }

    void deliverFeedback(AgentJob job, @Nullable DeliveryContent delivery) {
        deliverFeedback(job, delivery, Set.of());
    }

    void recordProposal(
            AgentJob job,
            @Nullable DeliveryContent delivery,
            List<PracticeDetectionResultParser.ValidatedObservation> observations) {
        feedbackLedgerRecorder.recordProposal(job, delivery, observations);
    }

    ExistingDeliveryLookup findExistingSummary(AgentJob job) {
        return commentPoster.findExistingSummaryComment(job);
    }

    boolean recoverAutomaticPackageIfPresent(AgentJob job) {
        FeedbackDispatch existing = dispatchService.findAutomaticPackage(job).orElse(null);
        if (existing == null) return false;
        PracticeFeedbackDispatchService.Result result = dispatchService.recover(existing, job);
        FeedbackDispatch recovered = dispatchService.automaticPackage(job);
        if (isTerminal(recovered.getState())) projectAutomaticPackage(job, recovered);
        if (result.status() == PracticeFeedbackDispatchService.Result.Status.SENT) {
            job.setDeliveryCommentId(result.externalRef());
            return true;
        }
        if (result.status() == PracticeFeedbackDispatchService.Result.Status.SUPPRESSED) return true;
        throw new JobDeliveryException("Review package dispatch is awaiting reconciliation: jobId=" + job.getId());
    }

    void deliverFeedback(AgentJob job, @Nullable DeliveryContent delivery, Set<String> contributingPracticeSlugs) {
        if (delivery == null) {
            log.debug("No delivery content, skipping: jobId={}", job.getId());
            return;
        }

        PracticeFeedbackDeliveryPolicy.Decision<PullRequest> decision =
                deliveryPolicy.evaluatePullRequest(job, DeliveryPolicyStage.AUTOMATIC, null, contributingPracticeSlugs);
        if (!decision.allowed()) {
            FeedbackSuppressionReason reason = decision.refusal();
            if (reason != null) recordGateSuppressed(job, delivery, reason);
            return;
        }

        DeliveryContent providerPackage = providerPackage(job, delivery);
        PracticeFeedbackDispatchService.Result result =
                dispatchService.dispatchAutomaticPackage(job, providerPackage, contributingPracticeSlugs);
        FeedbackDispatch dispatch = dispatchService.automaticPackage(job);
        if (isTerminal(dispatch.getState())) projectAutomaticPackage(job, dispatch);

        if (result.status() == PracticeFeedbackDispatchService.Result.Status.SENT) {
            job.setDeliveryCommentId(result.externalRef());
            return;
        }
        if (result.status() == PracticeFeedbackDispatchService.Result.Status.SUPPRESSED) return;
        throw new JobDeliveryException("Review package dispatch is awaiting reconciliation: jobId=" + job.getId());
    }

    void projectAutomaticPackage(AgentJob job, FeedbackDispatch dispatch) {
        dispatchService.projectRecovered(dispatch, () -> {
            DeliveryContent delivery = dispatchService.packageContent(dispatch);
            List<DeliveredSignal> signals = dispatchService.deliveredSignals(dispatch);
            var artifactKind = artifactKind(job);
            boolean summaryDelivered = dispatch.getDeliveredExternalRef() != null;
            boolean inlineDelivered = signals.stream().anyMatch(signal -> signal.disposition() != Disposition.FAILED);

            if (dispatch.getState() == FeedbackDispatchState.SENT) {
                feedbackLedgerRecorder.record(
                        job, delivery, artifactKind, signals, dispatch.getDeliveredExternalRef(), inlineDelivered);
                reconcileJob(dispatch, DeliveryStatus.DELIVERED);
                return;
            }
            if (dispatch.getState() == FeedbackDispatchState.SUPPRESSED) {
                FeedbackSuppressionReason reason = FeedbackSuppressionReason.valueOf(
                        java.util.Objects.requireNonNull(dispatch.getSuppressionReason()));
                if (!summaryDelivered && !inlineDelivered) {
                    feedbackLedgerRecorder.recordSuppressedUnit(job, delivery, reason);
                    reconcileJob(dispatch, DeliveryStatus.DELIVERED);
                    return;
                }
                feedbackLedgerRecorder.recordWithoutConversation(
                        job, delivery, artifactKind, signals, dispatch.getDeliveredExternalRef(), inlineDelivered);
                feedbackLedgerRecorder.recordSuppressedRemainder(
                        job, delivery, reason, missingInlineKeys(delivery, signals));
                reconcileJob(dispatch, DeliveryStatus.DELIVERED);
                return;
            }
            if (dispatch.getState() == FeedbackDispatchState.FAILED) {
                if (summaryDelivered || inlineDelivered) {
                    feedbackLedgerRecorder.recordWithoutConversation(
                            job, delivery, artifactKind, signals, dispatch.getDeliveredExternalRef(), inlineDelivered);
                } else {
                    feedbackLedgerRecorder.recordUndelivered(job, delivery);
                }
                reconcileJob(dispatch, DeliveryStatus.FAILED);
            }
        });
    }

    private void reconcileJob(FeedbackDispatch dispatch, DeliveryStatus status) {
        agentJobRepository.reconcileDispatchDeliveryStatus(
                dispatch.getAgentJobId(), dispatch.getWorkspaceId(), status, dispatch.getDeliveredExternalRef());
    }

    private DeliveryContent providerPackage(AgentJob job, DeliveryContent delivery) {
        String summary = delivery.mrNote();
        if (summary == null) return delivery;
        String sanitized = PullRequestCommentPoster.sanitize(summary);
        if (sanitized.isBlank()) return new DeliveryContent(null, delivery.diffNotes(), delivery.withheld());
        return new DeliveryContent(commentFormatter.format(sanitized, job), delivery.diffNotes(), delivery.withheld());
    }

    private static List<String> missingInlineKeys(DeliveryContent delivery, List<DeliveredSignal> signals) {
        Set<String> delivered = signals.stream()
                .filter(signal -> signal.disposition() != Disposition.FAILED)
                .map(DeliveredSignal::deliveryKey)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        return delivery.diffNotes().stream()
                .map(PracticeDetectionResultParser.DiffNote::deliveryKey)
                .filter(java.util.Objects::nonNull)
                .filter(key -> !delivered.contains(key))
                .toList();
    }

    private static boolean isTerminal(FeedbackDispatchState state) {
        return (state == FeedbackDispatchState.SENT
                || state == FeedbackDispatchState.SUPPRESSED
                || state == FeedbackDispatchState.FAILED);
    }

    private static ArtifactKind artifactKind(AgentJob job) {
        var artifact = AgentJobService.artifactKindFor(java.util.Objects.requireNonNull(job.getJobType()));
        if (artifact.equals(ArtifactKinds.PULL_REQUEST) || artifact.equals(ArtifactKinds.ISSUE)) return artifact;
        throw new JobDeliveryException("Artifact package projection does not support " + artifact.value());
    }

    private void recordGateSuppressed(AgentJob job, DeliveryContent delivery, FeedbackSuppressionReason reason) {
        try {
            feedbackLedgerRecorder.recordSuppressedUnit(job, delivery, reason);
        } catch (RuntimeException exception) {
            log.warn(
                    "Gate-suppressed ledger record failed: jobId={}, reason={}, error={}",
                    job.getId(),
                    reason,
                    exception.getMessage());
        }
    }
}
