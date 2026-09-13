package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.DeliveryContent;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.DiffNote;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ExistingDeliveryLookup;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliverySuppressedException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobService;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.DeliveredSignal;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyStage;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatch;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchDestination;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchInsert;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementType;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
class PracticeFeedbackDispatchService {

    static final Duration LEASE = Duration.ofMinutes(5);
    static final int MAX_ATTEMPTS = 8;

    private final FeedbackDispatchRepository repository;
    private final PracticeFeedbackDeliveryPolicy policy;
    private final PullRequestCommentPoster commentPoster;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final FeedbackRepository feedbackRepository;
    private final DiffNotePoster diffNotePoster;
    private final FeedbackDispatchStateMachine stateMachine;

    PracticeFeedbackDispatchService(
            FeedbackDispatchRepository repository,
            PracticeFeedbackDeliveryPolicy policy,
            PullRequestCommentPoster commentPoster,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            FeedbackRepository feedbackRepository,
            DiffNotePoster diffNotePoster,
            FeedbackDispatchStateMachine stateMachine) {
        this.repository = repository;
        this.policy = policy;
        this.commentPoster = commentPoster;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.feedbackRepository = feedbackRepository;
        this.diffNotePoster = diffNotePoster;
        this.stateMachine = stateMachine;
    }

    Result dispatchAutomaticPackage(
            AgentJob job, DeliveryContent packageContent, Set<String> contributingPracticeSlugs) {
        return dispatch(
                insertIfAbsentAndLoad(
                        job,
                        null,
                        "review:" + job.getId(),
                        FeedbackDispatchDestination.AUTOMATIC_REVIEW_PACKAGE,
                        contributingPracticeSlugs,
                        packageContent),
                job);
    }

    Result dispatchApproved(AgentJob job, Feedback feedback) {
        return dispatch(
                insertIfAbsentAndLoad(
                        job,
                        feedback.getId(),
                        "approved:" + feedback.getId(),
                        FeedbackDispatchDestination.APPROVED_REVIEW_PACKAGE,
                        Set.copyOf(feedback.getProposedPracticeSlugs()),
                        new DeliveryContent(
                                java.util.Objects.requireNonNull(feedback.getBody()),
                                proposedInlineNotes(feedback),
                                List.of())),
                job);
    }

    private FeedbackDispatch insertIfAbsentAndLoad(
            AgentJob job,
            @Nullable UUID feedbackId,
            String key,
            FeedbackDispatchDestination destination,
            Set<String> contributingPracticeSlugs,
            DeliveryContent packageContent) {
        Long workspaceId = job.getWorkspace().getId();
        transactionTemplate.executeWithoutResult(status -> repository.insertIfAbsent(new FeedbackDispatchInsert(
                UUID.randomUUID(),
                key,
                workspaceId,
                job.getId(),
                feedbackId,
                destination.name(),
                packageContent.mrNote() == null ? "" : packageContent.mrNote(),
                objectMapper
                        .valueToTree(contributingPracticeSlugs.stream().sorted().toList())
                        .toString(),
                objectMapper.valueToTree(packageContent).toString())));
        FeedbackDispatch dispatch = repository
                .findByDestinationKeyAndWorkspaceId(key, workspaceId)
                .orElseThrow(() -> new JobDeliveryException("Dispatch intent was not persisted: " + key));
        if (dispatch.getDestination() != destination) {
            throw new JobDeliveryException("Dispatch key was reused for a different destination: " + key);
        }
        return dispatch;
    }

    private Result dispatch(FeedbackDispatch dispatch, AgentJob job) {
        if (dispatch.getState() == FeedbackDispatchState.SENT) {
            return Result.sent(dispatch.getDeliveredExternalRef(), deliveredSignals(dispatch));
        }
        if (dispatch.getState() == FeedbackDispatchState.SUPPRESSED) {
            return Result.suppressed(
                    storedReason(dispatch), dispatch.getDeliveredExternalRef(), deliveredSignals(dispatch));
        }
        if (dispatch.getState() == FeedbackDispatchState.FAILED) {
            return Result.failed(dispatch.getDeliveredExternalRef(), deliveredSignals(dispatch));
        }

        String owner = UUID.randomUUID().toString();
        Integer claimed = transactionTemplate.execute(status -> repository.claim(
                dispatch.getId(),
                dispatch.getWorkspaceId(),
                owner,
                Instant.now().plus(LEASE),
                MAX_ATTEMPTS));
        if (claimed == null || claimed == 0) {
            return Result.inProgress();
        }

        if (dispatch.getDestination() == FeedbackDispatchDestination.APPROVED_REVIEW_PACKAGE) {
            return dispatchApprovedPackage(dispatch, job, owner);
        }

        return dispatchAutomaticPackage(dispatch, job, owner);
    }

    private Result dispatchAutomaticPackage(FeedbackDispatch dispatch, AgentJob job, String owner) {
        @Nullable String summaryRef = dispatch.getDeliveredExternalRef();
        List<DeliveredSignal> inlineSignals = deliveredSignals(dispatch);
        boolean writeBegan = false;
        try {
            boolean hasSummary = !dispatch.getBody().isBlank();
            if (hasSummary && summaryRef == null) {
                ExistingDeliveryLookup existing = commentPoster.findExistingSummaryComment(job);
                if (existing.kind() == ExistingDeliveryLookup.Kind.UNKNOWN) {
                    return stateMachine.retry(dispatch, owner, "Provider lookup was inconclusive");
                }
                if (existing.kind() == ExistingDeliveryLookup.Kind.FOUND) {
                    summaryRef = existing.commentId();
                } else if (dispatch.getWriteStarted()) {
                    return stateMachine.retry(dispatch, owner, "A prior provider write has not been reconciled");
                }
            }

            if (hasSummary && summaryRef == null) {
                PracticeFeedbackDeliveryPolicy.Decision<?> decision = evaluateAtEgress(dispatch, job);
                if (!decision.allowed()) return stateMachine.refuse(dispatch, owner, decision.refusal());
                Integer began = transactionTemplate.execute(
                        status -> repository.beginWrite(dispatch.getId(), dispatch.getWorkspaceId(), owner));
                if (began == null || began != 1) return Result.inProgress();
                writeBegan = true;
                summaryRef = java.util.Objects.requireNonNull(post(dispatch, job));
            }

            List<DiffNote> inlineNotes = inlineNotes(dispatch);
            if (!isIssue(job)) {
                PracticeFeedbackDeliveryPolicy.Decision<?> decision = evaluateAtEgress(dispatch, job);
                if (!decision.allowed())
                    return stateMachine.refuse(dispatch, owner, decision.refusal(), summaryRef, inlineSignals);
                DiffNotePoster.DiffNoteResult inline = diffNotePoster.reconcileInlineNotes(job, inlineNotes);
                inlineSignals = stateMachine.mergeSignals(inlineSignals, inline.signals());
                if (inline.failed() > 0 || inline.suppressed()) {
                    return stateMachine.retryPackage(
                            dispatch, owner, "Automatic review package remains incomplete", summaryRef, inlineSignals);
                }
            }

            return stateMachine.sent(dispatch, owner, summaryRef, inlineSignals);
        } catch (JobDeliverySuppressedException exception) {
            return stateMachine.refuse(
                    dispatch, owner, FeedbackSuppressionReason.INSTANCE_SILENCED, summaryRef, inlineSignals);
        } catch (RuntimeException exception) {
            if (summaryRef != null) {
                return stateMachine.retryPackage(dispatch, owner, exception.getMessage(), summaryRef, inlineSignals);
            }
            return writeBegan
                    ? stateMachine.retryAfterWrite(dispatch, owner, exception.getMessage())
                    : stateMachine.retry(dispatch, owner, exception.getMessage());
        }
    }

    private Result dispatchApprovedPackage(FeedbackDispatch dispatch, AgentJob job, String owner) {
        Feedback feedback = feedbackRepository
                .findByIdAndWorkspaceId(dispatch.approvedFeedbackId(), dispatch.getWorkspaceId())
                .orElse(null);
        if (feedback == null
                || feedback.getBody() == null
                || !feedback.getBody().equals(dispatch.getBody())) {
            return stateMachine.retry(
                    dispatch, owner, "Approved feedback is missing or no longer matches its immutable body");
        }
        @Nullable String summaryRef = dispatch.getDeliveredExternalRef();
        List<DeliveredSignal> inlineSignals = deliveredSignals(dispatch);
        boolean writeBegan = false;
        try {
            if (summaryRef == null) {
                ExistingDeliveryLookup existing =
                        commentPoster.findApprovedProposal(job, dispatch.approvedFeedbackId());
                if (existing.kind() == ExistingDeliveryLookup.Kind.UNKNOWN) {
                    return stateMachine.retry(dispatch, owner, "Provider lookup was inconclusive");
                }
                if (existing.kind() == ExistingDeliveryLookup.Kind.FOUND) {
                    summaryRef = java.util.Objects.requireNonNull(existing.commentId());
                } else if (dispatch.getWriteStarted()) {
                    return stateMachine.retry(dispatch, owner, "A prior provider write has not been reconciled");
                }
            }

            if (summaryRef == null) {
                PracticeFeedbackDeliveryPolicy.Decision<?> decision = evaluateAtEgress(dispatch, job);
                if (!decision.allowed()) return stateMachine.refuse(dispatch, owner, decision.refusal());
                if (!reviewedRevisionMatches(feedback, decision)) {
                    return stateMachine.refuse(dispatch, owner, FeedbackSuppressionReason.APPROVAL_STALE);
                }
                Integer began = transactionTemplate.execute(
                        status -> repository.beginWrite(dispatch.getId(), dispatch.getWorkspaceId(), owner));
                if (began == null || began != 1) return Result.inProgress();
                writeBegan = true;
                summaryRef = java.util.Objects.requireNonNull(
                        commentPoster.postApprovedProposal(job, dispatch.approvedFeedbackId(), dispatch.getBody()));
            }

            String deliveredSummaryRef = java.util.Objects.requireNonNull(summaryRef);
            var inlineNotes = inlineNotes(dispatch);
            if (!inlineNotes.isEmpty()) {
                PracticeFeedbackDeliveryPolicy.Decision<?> decision = evaluateAtEgress(dispatch, job);
                if (!decision.allowed()) {
                    return stateMachine.refuse(dispatch, owner, decision.refusal(), deliveredSummaryRef, inlineSignals);
                }
                if (!reviewedRevisionMatches(feedback, decision)) {
                    return stateMachine.refuse(
                            dispatch,
                            owner,
                            FeedbackSuppressionReason.APPROVAL_STALE,
                            deliveredSummaryRef,
                            inlineSignals);
                }
                DiffNotePoster.DiffNoteResult inline =
                        diffNotePoster.reconcileApprovedInlineNotes(job, feedback.getId(), inlineNotes);
                inlineSignals = stateMachine.mergeSignals(inlineSignals, inline.signals());
                if (inline.failed() > 0 || inline.suppressed()) {
                    return stateMachine.retryPackage(
                            dispatch,
                            owner,
                            "Approved review package remains incomplete",
                            deliveredSummaryRef,
                            inlineSignals);
                }
            }
            return stateMachine.sent(dispatch, owner, deliveredSummaryRef, inlineSignals);
        } catch (JobDeliverySuppressedException exception) {
            return stateMachine.refuse(
                    dispatch, owner, FeedbackSuppressionReason.INSTANCE_SILENCED, summaryRef, inlineSignals);
        } catch (RuntimeException exception) {
            if (summaryRef != null) {
                return stateMachine.retryPackage(dispatch, owner, exception.getMessage(), summaryRef, inlineSignals);
            }
            if (writeBegan) {
                return stateMachine.retryAfterWrite(dispatch, owner, exception.getMessage());
            }
            return stateMachine.retry(dispatch, owner, exception.getMessage());
        }
    }

    private static boolean reviewedRevisionMatches(
            Feedback feedback, PracticeFeedbackDeliveryPolicy.Decision<?> decision) {
        if (feedback.getReviewedRevision() == null) return true;
        return (decision.target() instanceof PullRequest pullRequest
                && feedback.getReviewedRevision().equals(pullRequest.getHeadRefOid()));
    }

    private PracticeFeedbackDeliveryPolicy.Decision<?> evaluateAtEgress(FeedbackDispatch dispatch, AgentJob job) {
        Set<String> practiceSlugs = dispatch.getPracticeSlugs()
                .valueStream()
                .filter(tools.jackson.databind.JsonNode::isString)
                .map(tools.jackson.databind.JsonNode::asString)
                .collect(Collectors.toUnmodifiableSet());
        if (isIssue(job)) {
            return policy.evaluateIssue(job, DeliveryPolicyStage.EGRESS, dispatch.getFeedbackId(), practiceSlugs);
        }
        return policy.evaluatePullRequest(job, DeliveryPolicyStage.EGRESS, dispatch.getFeedbackId(), practiceSlugs);
    }

    private List<DiffNote> inlineNotes(FeedbackDispatch dispatch) {
        return packageContent(dispatch).diffNotes();
    }

    DeliveryContent packageContent(FeedbackDispatch dispatch) {
        return objectMapper.convertValue(dispatch.packageContent(), DeliveryContent.class);
    }

    FeedbackDispatch automaticPackage(AgentJob job) {
        return findAutomaticPackage(job)
                .orElseThrow(() -> new JobDeliveryException("Automatic review package was not persisted"));
    }

    Optional<FeedbackDispatch> findAutomaticPackage(AgentJob job) {
        return repository.findByDestinationKeyAndWorkspaceId(
                "review:" + job.getId(), job.getWorkspace().getId());
    }

    List<DeliveredSignal> deliveredSignals(FeedbackDispatch dispatch) {
        return stateMachine.deliveredSignals(dispatch);
    }

    private static List<DiffNote> proposedInlineNotes(Feedback feedback) {
        return feedback.getProposedPlacements().stream()
                .filter(placement -> placement.type() == PlacementType.INLINE)
                .map(placement -> new DiffNote(
                        java.util.Objects.requireNonNull(placement.path()),
                        java.util.Objects.requireNonNull(placement.startLine()),
                        placement.endLine(),
                        placement.body(),
                        placement.deliveryKey()))
                .toList();
    }

    private @Nullable String post(FeedbackDispatch dispatch, AgentJob job) {
        if (dispatch.getDestination() == FeedbackDispatchDestination.APPROVED_REVIEW_PACKAGE) {
            if (isIssue(job)) {
                return commentPoster.postIssueApprovedProposal(job, dispatch.approvedFeedbackId(), dispatch.getBody());
            }
            return commentPoster.postApprovedProposal(job, dispatch.approvedFeedbackId(), dispatch.getBody());
        }
        return isIssue(job)
                ? commentPoster.postIssueFormattedBody(job, dispatch.getBody())
                : commentPoster.postFormattedBody(job, dispatch.getBody());
    }

    private static boolean isIssue(AgentJob job) {
        var artifact = AgentJobService.artifactKindFor(java.util.Objects.requireNonNull(job.getJobType()));
        if (artifact.equals(ArtifactKinds.ISSUE)) return true;
        if (artifact.equals(ArtifactKinds.PULL_REQUEST)) return false;
        throw new JobDeliveryException("Artifact dispatch does not support " + artifact.value());
    }

    private static @Nullable FeedbackSuppressionReason storedReason(FeedbackDispatch dispatch) {
        String stored = dispatch.getSuppressionReason();
        return stored == null ? null : FeedbackSuppressionReason.valueOf(stored);
    }

    Result recover(FeedbackDispatch dispatch, AgentJob job) {
        return dispatch(dispatch, job);
    }

    boolean projectApproved(Feedback feedback, Runnable projection) {
        return projectByKey("approved:" + feedback.getId(), feedback.getWorkspaceId(), projection);
    }

    boolean projectRecovered(FeedbackDispatch dispatch, Runnable projection) {
        String owner = UUID.randomUUID().toString();
        Integer claimed = transactionTemplate.execute(status -> repository.claimProjection(
                dispatch.getId(),
                dispatch.getWorkspaceId(),
                owner,
                Instant.now().plus(LEASE)));
        if (claimed == null || claimed != 1) return false;
        projection.run();
        Integer projected = transactionTemplate.execute(
                status -> repository.markProjected(dispatch.getId(), dispatch.getWorkspaceId(), owner));
        return projected != null && projected == 1;
    }

    private boolean projectByKey(String destinationKey, Long workspaceId, Runnable projection) {
        String owner = UUID.randomUUID().toString();
        Integer claimed = transactionTemplate.execute(status -> repository.claimProjectionByKey(
                destinationKey, workspaceId, owner, Instant.now().plus(LEASE)));
        if (claimed == null || claimed != 1) return false;
        projection.run();
        Integer projected = transactionTemplate.execute(
                status -> repository.markProjectedByKey(destinationKey, workspaceId, owner));
        return projected != null && projected == 1;
    }

    void fail(FeedbackDispatch dispatch, String error) {
        stateMachine.fail(dispatch, error);
    }

    record Result(
            Status status,
            @Nullable String externalRef,
            @Nullable FeedbackSuppressionReason suppressionReason,
            List<DeliveredSignal> deliveredSignals) {
        FeedbackSuppressionReason refusal() {
            return java.util.Objects.requireNonNull(suppressionReason, "a refused dispatch always names its reason");
        }

        String sentRef() {
            return java.util.Objects.requireNonNull(externalRef, "a sent dispatch always has a provider id");
        }

        static Result sent(@Nullable String ref) {
            return sent(ref, List.of());
        }

        static Result sent(@Nullable String ref, List<DeliveredSignal> signals) {
            return new Result(Status.SENT, ref, null, List.copyOf(signals));
        }

        static Result suppressed(@Nullable FeedbackSuppressionReason reason) {
            return suppressed(reason, null);
        }

        static Result suppressed(@Nullable FeedbackSuppressionReason reason, @Nullable String ref) {
            return suppressed(reason, ref, List.of());
        }

        static Result suppressed(
                @Nullable FeedbackSuppressionReason reason, @Nullable String ref, List<DeliveredSignal> signals) {
            return new Result(Status.SUPPRESSED, ref, reason, List.copyOf(signals));
        }

        static Result uncertain(@Nullable String ref) {
            return new Result(Status.UNCERTAIN, ref, null, List.of());
        }

        static Result uncertain() {
            return uncertain(null);
        }

        static Result inProgress() {
            return new Result(Status.IN_PROGRESS, null, null, List.of());
        }

        static Result failed() {
            return failed(null, List.of());
        }

        static Result failed(@Nullable String ref) {
            return failed(ref, List.of());
        }

        static Result failed(@Nullable String ref, List<DeliveredSignal> signals) {
            return new Result(Status.FAILED, ref, null, List.copyOf(signals));
        }

        enum Status {
            SENT,
            SUPPRESSED,
            UNCERTAIN,
            IN_PROGRESS,
            FAILED,
        }
    }
}
