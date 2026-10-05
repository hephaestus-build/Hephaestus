package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.DeliveryContent;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobService;
import de.tum.cit.aet.hephaestus.agent.job.DeliveryStatus;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.DeliveredSignal;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyStage;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatch;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class FeedbackDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackDeliveryService.class);

    private final PracticeFeedbackDeliveryPolicy deliveryPolicy;
    private final FeedbackLedgerRecorder feedbackLedgerRecorder;
    private final PracticeFeedbackCommentFormatter commentFormatter;
    private final PracticeFeedbackDispatchService dispatchService;
    private final AgentJobRepository agentJobRepository;

    FeedbackDeliveryService(
            PracticeFeedbackDeliveryPolicy deliveryPolicy,
            FeedbackLedgerRecorder feedbackLedgerRecorder,
            PracticeFeedbackCommentFormatter commentFormatter,
            PracticeFeedbackDispatchService dispatchService,
            AgentJobRepository agentJobRepository) {
        this.deliveryPolicy = deliveryPolicy;
        this.feedbackLedgerRecorder = feedbackLedgerRecorder;
        this.commentFormatter = commentFormatter;
        this.dispatchService = dispatchService;
        this.agentJobRepository = agentJobRepository;
    }

    void deliverFeedback(AgentJob job, @Nullable DeliveryContent delivery) {
        deliverFeedback(job, delivery, Set.of());
    }

    void recordProposal(AgentJob job, @Nullable DeliveryContent delivery) {
        feedbackLedgerRecorder.recordProposal(job, delivery);
    }

    boolean recoverAutomaticPackageIfPresent(AgentJob job) {
        FeedbackDispatch existing = dispatchService.findAutomaticPackage(job).orElse(null);
        if (existing == null) return false;
        PracticeFeedbackDispatchService.Result result = dispatchService.recover(existing, job);
        FeedbackDispatch recovered = dispatchService.automaticPackage(job);
        recordAutomaticPackage(job, recovered);
        if (result.status() == PracticeFeedbackDispatchService.Result.Status.SENT) {
            job.setDeliveryCommentId(result.externalRef());
            return true;
        }
        if (result.status() == PracticeFeedbackDispatchService.Result.Status.SUPPRESSED) return true;
        throw new JobDeliveryException(
                "The dispatch of the review package waits for reconciliation. jobId=" + job.getId());
    }

    void deliverFeedback(AgentJob job, @Nullable DeliveryContent delivery, Set<String> contributingPracticeSlugs) {
        if (delivery == null
                || (delivery.mrNote() == null && delivery.diffNotes().isEmpty())) {
            feedbackLedgerRecorder.recordNothingToPost(job, delivery);
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
        // Blank notes keep their place so each note keeps its key and support; they carry nothing to post.
        if (providerPackage.mrNote() == null
                && providerPackage.diffNotes().stream()
                        .allMatch(note -> note.body().isBlank())) {
            recordGateSuppressed(job, delivery, FeedbackSuppressionReason.EMPTY_AFTER_SANITIZE);
            return;
        }
        PracticeFeedbackDispatchService.Result result =
                dispatchService.dispatchAutomaticPackage(job, providerPackage, contributingPracticeSlugs);
        FeedbackDispatch dispatch = dispatchService.automaticPackage(job);
        recordAutomaticPackage(job, dispatch);

        if (result.status() == PracticeFeedbackDispatchService.Result.Status.SENT) {
            job.setDeliveryCommentId(result.externalRef());
            return;
        }
        if (result.status() == PracticeFeedbackDispatchService.Result.Status.SUPPRESSED) return;
        throw new JobDeliveryException(
                "The dispatch of the review package waits for reconciliation. jobId=" + job.getId());
    }

    /**
     * Projects a settled package; one still settling records only the copies it has placed so far, so a correction
     * can reach them before the rest of the package is known.
     */
    void recordAutomaticPackage(AgentJob job, FeedbackDispatch dispatch) {
        if (isTerminal(dispatch.getState())) {
            projectAutomaticPackage(job, dispatch);
            return;
        }
        List<DeliveredSignal> signals = dispatchService.deliveredSignals(dispatch);
        boolean inlineDelivered = signals.stream().anyMatch(DeliveredSignal::acknowledged);
        if (dispatch.getDeliveredExternalRef() == null && !inlineDelivered) return;
        feedbackLedgerRecorder.recordWithoutConversation(
                job,
                dispatchService.packageContent(dispatch),
                artifactKind(job),
                signals,
                dispatch.getDeliveredExternalRef(),
                dispatch.getDeliveredExternalUrl());
    }

    void projectAutomaticPackage(AgentJob job, FeedbackDispatch dispatch) {
        dispatchService.projectRecovered(dispatch, () -> {
            DeliveryContent delivery = dispatchService.packageContent(dispatch);
            List<DeliveredSignal> signals = dispatchService.deliveredSignals(dispatch);
            var artifactKind = artifactKind(job);
            boolean summaryDelivered = dispatch.getDeliveredExternalRef() != null;
            boolean inlineDelivered = signals.stream().anyMatch(DeliveredSignal::acknowledged);

            if (dispatch.getState() == FeedbackDispatchState.SENT) {
                feedbackLedgerRecorder.record(
                        job,
                        delivery,
                        artifactKind,
                        signals,
                        dispatch.getDeliveredExternalRef(),
                        dispatch.getDeliveredExternalUrl());
                reconcileJob(dispatch, DeliveryStatus.DELIVERED);
                return;
            }
            if (dispatch.getState() == FeedbackDispatchState.SUPPRESSED) {
                FeedbackSuppressionReason reason =
                        FeedbackSuppressionReason.valueOf(Objects.requireNonNull(dispatch.getSuppressionReason()));
                if (!summaryDelivered && !inlineDelivered) {
                    feedbackLedgerRecorder.recordSuppressedUnit(job, delivery, reason);
                    reconcileJob(dispatch, DeliveryStatus.DELIVERED);
                    return;
                }
                feedbackLedgerRecorder.recordWithoutConversation(
                        job,
                        delivery,
                        artifactKind,
                        signals,
                        dispatch.getDeliveredExternalRef(),
                        dispatch.getDeliveredExternalUrl());
                feedbackLedgerRecorder.recordSuppressedRemainder(
                        job, delivery, reason, missingInlineKeys(delivery, signals));
                reconcileJob(dispatch, DeliveryStatus.DELIVERED);
                return;
            }
            if (dispatch.getState() == FeedbackDispatchState.FAILED) {
                if (summaryDelivered || inlineDelivered) {
                    feedbackLedgerRecorder.recordWithoutConversation(
                            job,
                            delivery,
                            artifactKind,
                            signals,
                            dispatch.getDeliveredExternalRef(),
                            dispatch.getDeliveredExternalUrl());
                    feedbackLedgerRecorder.recordUndeliveredRemainder(
                            job, delivery, missingInlineKeys(delivery, signals));
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

    /**
     * The package as the provider will show it, sealed before it is persisted: the summary with its disclosure, each
     * line note's provider-safe text with its own, and the marker that scopes this package's inline copies. A retry
     * posts and reads back exactly this text instead of rebuilding it.
     */
    private DeliveryContent providerPackage(AgentJob job, DeliveryContent delivery) {
        List<ReviewResultParser.DiffNote> notes = delivery.diffNotes().stream()
                .map(note -> {
                    String sanitized = PullRequestCommentPoster.sanitize(note.body());
                    return new ReviewResultParser.DiffNote(
                            note.filePath(),
                            note.startLine(),
                            note.endLine(),
                            sanitized.isBlank() ? "" : commentFormatter.appendInlineFeedbackPrompt(sanitized, job),
                            note.deliveryKey(),
                            note.contributors());
                })
                .toList();
        String marker = InlinePackageScope.automaticMarker(job.getId());
        String summary = delivery.mrNote();
        String sanitized = summary == null ? "" : PullRequestCommentPoster.sanitize(summary);
        if (sanitized.isBlank())
            return new DeliveryContent(
                    null,
                    notes,
                    delivery.withheld(),
                    delivery.summaryContributors() == null ? null : List.of(),
                    marker);
        return new DeliveryContent(
                commentFormatter.format(sanitized, job),
                notes,
                delivery.withheld(),
                delivery.summaryContributors(),
                marker);
    }

    private static List<String> missingInlineKeys(DeliveryContent delivery, List<DeliveredSignal> signals) {
        Set<String> delivered = signals.stream()
                .filter(DeliveredSignal::acknowledged)
                .map(DeliveredSignal::deliveryKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return delivery.diffNotes().stream()
                .map(ReviewResultParser.DiffNote::deliveryKey)
                .filter(Objects::nonNull)
                .filter(key -> !delivered.contains(key))
                .toList();
    }

    private static boolean isTerminal(FeedbackDispatchState state) {
        return (state == FeedbackDispatchState.SENT
                || state == FeedbackDispatchState.SUPPRESSED
                || state == FeedbackDispatchState.FAILED);
    }

    private static ArtifactKind artifactKind(AgentJob job) {
        var artifact = AgentJobService.artifactKindFor(Objects.requireNonNull(job.getJobType()));
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
