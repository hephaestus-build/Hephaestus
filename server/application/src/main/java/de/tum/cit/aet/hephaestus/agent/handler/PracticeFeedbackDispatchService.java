package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.DeliveryContent;
import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.DiffNote;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ExistingDeliveryLookup;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliverySuppressedException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobService;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.DeliveredSignal;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.SummaryHandle;
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
import de.tum.cit.aet.hephaestus.practices.observation.ObservationInvalidationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
class PracticeFeedbackDispatchService {

    static final Duration LEASE = Duration.ofMinutes(5);
    static final int MAX_ATTEMPTS = 8;
    /** How long after a write began an unconfirmed one is still expected; after it, it is checked every six hours. */
    static final Duration UNCONFIRMED_WINDOW = Duration.ofHours(24);

    static final Duration UNCONFIRMED_RECHECK = Duration.ofHours(6);

    private static final String REVISION_UNKNOWN = "The reviewed work could not be compared with the current work";

    private final FeedbackDispatchRepository repository;
    private final PracticeFeedbackDeliveryPolicy policy;
    private final PullRequestCommentPoster commentPoster;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final FeedbackRepository feedbackRepository;
    private final DiffNotePoster diffNotePoster;
    private final FeedbackDispatchStateMachine stateMachine;
    private final ObservationInvalidationRepository invalidations;
    private final RepeatedSummaryCheck repeatedSummaries;
    private final PracticeFeedbackPersonDataAdmission personDataAdmission;

    PracticeFeedbackDispatchService(
            FeedbackDispatchRepository repository,
            PracticeFeedbackDeliveryPolicy policy,
            PullRequestCommentPoster commentPoster,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            FeedbackRepository feedbackRepository,
            DiffNotePoster diffNotePoster,
            FeedbackDispatchStateMachine stateMachine,
            ObservationInvalidationRepository invalidations,
            RepeatedSummaryCheck repeatedSummaries,
            PracticeFeedbackPersonDataAdmission personDataAdmission) {
        this.repository = repository;
        this.policy = policy;
        this.commentPoster = commentPoster;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.feedbackRepository = feedbackRepository;
        this.diffNotePoster = diffNotePoster;
        this.stateMachine = stateMachine;
        this.invalidations = invalidations;
        this.repeatedSummaries = repeatedSummaries;
        this.personDataAdmission = personDataAdmission;
    }

    Result dispatchAutomaticPackage(
            AgentJob job, DeliveryContent packageContent, Set<String> contributingPracticeSlugs) {
        return personDataAdmission.deliver(
                job,
                () -> dispatch(
                        insertIfAbsentAndLoad(
                                job,
                                null,
                                "review:" + job.getId(),
                                FeedbackDispatchDestination.AUTOMATIC_REVIEW_PACKAGE,
                                contributingPracticeSlugs,
                                packageContent),
                        job));
    }

    Result dispatchApproved(AgentJob job, Feedback feedback) {
        return personDataAdmission.deliver(
                job,
                () -> dispatch(
                        insertIfAbsentAndLoad(
                                job,
                                feedback.getId(),
                                "approved:" + feedback.getId(),
                                FeedbackDispatchDestination.APPROVED_REVIEW_PACKAGE,
                                Set.copyOf(feedback.getProposedPracticeSlugs()),
                                new DeliveryContent(
                                        feedback.getBody(),
                                        proposedInlineNotes(feedback),
                                        List.of(),
                                        null,
                                        InlinePackageScope.approvedMarker(feedback.getId()))),
                        job));
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
            return Result.sent(
                    dispatch.getDeliveredExternalRef(), dispatch.getDeliveredExternalUrl(), deliveredSignals(dispatch));
        }
        if (dispatch.getState() == FeedbackDispatchState.SUPPRESSED) {
            return Result.suppressed(
                    storedReason(dispatch),
                    dispatch.getDeliveredExternalRef(),
                    dispatch.getDeliveredExternalUrl(),
                    deliveredSignals(dispatch));
        }
        if (dispatch.getState() == FeedbackDispatchState.FAILED) {
            return Result.failed(
                    dispatch.getDeliveredExternalRef(), dispatch.getDeliveredExternalUrl(), deliveredSignals(dispatch));
        }

        String owner = UUID.randomUUID().toString();
        Integer claimed = transactionTemplate.execute(status -> repository.claim(
                dispatch.getId(),
                dispatch.getWorkspaceId(),
                owner,
                Instant.now().plus(LEASE),
                MAX_ATTEMPTS,
                dispatch.getAttemptCount()));
        // The claim only succeeds on the count this dispatch was loaded with, so that count is this claim's own.
        if (claimed == null || claimed == 0) {
            return Result.inProgress();
        }

        if (Boolean.TRUE.equals(transactionTemplate.execute(status -> citesInvalidated(dispatch)))) {
            return refuseAfterReconciling(
                    dispatch,
                    job,
                    owner,
                    FeedbackSuppressionReason.OBSERVATION_INVALIDATED,
                    dispatch.getDeliveredExternalRef(),
                    dispatch.getDeliveredExternalUrl(),
                    deliveredSignals(dispatch));
        }
        // Past the budget only a write that may already have happened admitted the claim: reconcile, never write.
        if (dispatch.getAttemptCount() >= MAX_ATTEMPTS) {
            PracticeFeedbackDeliveryPolicy.Decision<?> decision = evaluateAtEgress(dispatch, job);
            if (!decision.allowed() && decision.refusal() == FeedbackSuppressionReason.PUBLIC_SUBJECT_INELIGIBLE) {
                return refuseAfterReconciling(
                        dispatch,
                        job,
                        owner,
                        decision.refusal(),
                        dispatch.getDeliveredExternalRef(),
                        dispatch.getDeliveredExternalUrl(),
                        deliveredSignals(dispatch));
            }
            return reconcileBeyondBudget(dispatch, job, owner);
        }
        return dispatch.getDestination() == FeedbackDispatchDestination.APPROVED_REVIEW_PACKAGE
                ? dispatchApprovedPackage(dispatch, job, owner)
                : dispatchAutomaticPackage(dispatch, job, owner);
    }

    private Result dispatchAutomaticPackage(FeedbackDispatch dispatch, AgentJob job, String owner) {
        @Nullable String summaryRef = dispatch.getDeliveredExternalRef();
        @Nullable String summaryUrl = dispatch.getDeliveredExternalUrl();
        List<DeliveredSignal> inlineSignals = deliveredSignals(dispatch);
        boolean writeBegan = false;
        try {
            boolean hasSummary = !dispatch.getBody().isBlank();
            if (!hasSummary && packageContent(dispatch).diffNotes().isEmpty()) {
                return stateMachine.refuse(dispatch, owner, FeedbackSuppressionReason.EMPTY_AFTER_SANITIZE);
            }
            if (hasSummary && summaryRef == null) {
                PullRequestCommentPoster.SummaryWrite write = summaryWrite(dispatch, job);
                ExistingDeliveryLookup existing = commentPoster.findExisting(write);
                if (existing.kind() == ExistingDeliveryLookup.Kind.UNKNOWN) {
                    return stateMachine.retry(dispatch, owner, "Provider lookup was inconclusive");
                }
                if (existing.kind() == ExistingDeliveryLookup.Kind.FOUND) {
                    summaryRef = existing.commentId();
                    summaryUrl = existing.commentUrl();
                } else if (dispatch.getWriteStarted()) {
                    return stateMachine.retry(dispatch, owner, "A prior provider write has not been reconciled");
                } else {
                    PracticeFeedbackDeliveryPolicy.Decision<?> decision = evaluateAtEgress(dispatch, job);
                    if (!decision.allowed())
                        return refuseAfterReconciling(
                                dispatch,
                                job,
                                owner,
                                decision.refusal(),
                                dispatch.getDeliveredExternalRef(),
                                dispatch.getDeliveredExternalUrl(),
                                deliveredSignals(dispatch));
                    switch (PracticeFeedbackDeliveryPolicy.reviewedRevision(job, decision)) {
                        case CHANGED -> {
                            return stateMachine.refuse(
                                    dispatch, owner, FeedbackSuppressionReason.REVIEWED_REVISION_CHANGED);
                        }
                        case UNKNOWN -> {
                            return stateMachine.retry(dispatch, owner, REVISION_UNKNOWN);
                        }
                        case CURRENT -> {}
                    }
                    if (packageContent(dispatch).diffNotes().isEmpty()
                            && repeatedSummaries.repeatsLastPosted(dispatch, job)) {
                        return stateMachine.refuse(dispatch, owner, FeedbackSuppressionReason.REPEATS_DELIVERED_NOTE);
                    }
                    var reservation = stateMachine.reserve(
                            () -> lockedDecision(dispatch, job, null),
                            () -> repository.beginWrite(dispatch.getId(), dispatch.getWorkspaceId(), owner));
                    switch (reservation.status()) {
                        case REFUSED -> {
                            return refuseAfterReconciling(
                                    dispatch,
                                    job,
                                    owner,
                                    reservation.refusal(),
                                    dispatch.getDeliveredExternalRef(),
                                    dispatch.getDeliveredExternalUrl(),
                                    deliveredSignals(dispatch));
                        }
                        case STALE -> {
                            return stateMachine.refuse(
                                    dispatch, owner, FeedbackSuppressionReason.REVIEWED_REVISION_CHANGED);
                        }
                        case UNKNOWN -> {
                            return stateMachine.retry(dispatch, owner, REVISION_UNKNOWN);
                        }
                        case LEASE_LOST -> {
                            return Result.inProgress();
                        }
                        case RESERVED -> {}
                    }
                    writeBegan = true;
                    SummaryHandle handle = commentPoster.post(write);
                    summaryRef = handle.externalId();
                    summaryUrl = handle.url();
                }
            }

            DeliveryContent sealed = packageContent(dispatch);
            if (!isIssue(job)) {
                PracticeFeedbackDeliveryPolicy.Decision<?> decision = evaluateAtEgress(dispatch, job);
                if (!decision.allowed())
                    return refuseAfterReconciling(
                            dispatch, job, owner, decision.refusal(), summaryRef, summaryUrl, inlineSignals);
                // An empty package writes nothing inline: it never retires what earlier packages placed.
                if (!sealed.diffNotes().isEmpty()) {
                    // While no inline write began nothing needs reading back, so a head proven moved is refused
                    // here and one that cannot be compared waits; otherwise both are met at each create, after the
                    // copies an earlier attempt may have made are read back.
                    if (!dispatch.inlineWriteMayHaveStarted()) {
                        switch (PracticeFeedbackDeliveryPolicy.reviewedRevision(job, decision)) {
                            case CHANGED -> {
                                return stateMachine.refuse(
                                        dispatch,
                                        owner,
                                        FeedbackSuppressionReason.REVIEWED_REVISION_CHANGED,
                                        summaryRef,
                                        summaryUrl,
                                        inlineSignals);
                            }
                            case UNKNOWN -> {
                                return stateMachine.retryPackage(
                                        dispatch, owner, REVISION_UNKNOWN, summaryRef, summaryUrl, inlineSignals);
                            }
                            case CURRENT -> {}
                        }
                    }
                    DiffNotePoster.DiffNoteResult inline = diffNotePoster.deliverPackage(
                            job,
                            InlinePackageScope.of(dispatch, sealed.inlineMarker(), null),
                            sealed.diffNotes(),
                            inlineSignals,
                            () -> policy.currentReviewedRevision(job, null),
                            receipt -> stateMachine.recordInlineAttempt(
                                    dispatch, owner, receipt, () -> lockedDecision(dispatch, job, null)));
                    inlineSignals = inline.signals();
                    if (inline.leaseLost()) return Result.inProgress();
                    if (inline.refusalReason() != null && !inline.unconfirmed()) {
                        return stateMachine.refuse(
                                dispatch, owner, inline.refusalReason(), summaryRef, summaryUrl, inlineSignals);
                    }
                    if (inline.revisionChanged() && !inline.unconfirmed()) {
                        return stateMachine.refuse(
                                dispatch,
                                owner,
                                FeedbackSuppressionReason.REVIEWED_REVISION_CHANGED,
                                summaryRef,
                                summaryUrl,
                                inlineSignals);
                    }
                    if (!inline.complete()) {
                        return inline.unconfirmed()
                                ? stateMachine.awaitUnconfirmed(dispatch, owner, summaryRef, summaryUrl, inlineSignals)
                                : stateMachine.retryPackage(
                                        dispatch,
                                        owner,
                                        "Automatic review package remains incomplete",
                                        summaryRef,
                                        summaryUrl,
                                        inlineSignals);
                    }
                }
            }

            return stateMachine.sent(dispatch, owner, summaryRef, summaryUrl, inlineSignals);
        } catch (JobDeliverySuppressedException exception) {
            return stateMachine.refuse(
                    dispatch,
                    owner,
                    FeedbackSuppressionReason.INSTANCE_SILENCED,
                    summaryRef,
                    summaryUrl,
                    inlineSignals);
        } catch (PullRequestCommentPoster.SummaryNotSentException exception) {
            return retryUnsent(dispatch, owner, exception.getMessage());
        } catch (RuntimeException exception) {
            return stateMachine.retryFailedAttempt(
                    dispatch, owner, exception.getMessage(), writeBegan, summaryRef, summaryUrl, inlineSignals);
        }
    }

    private Result dispatchApprovedPackage(FeedbackDispatch dispatch, AgentJob job, String owner) {
        Feedback feedback = feedbackRepository
                .findByIdAndWorkspaceId(dispatch.approvedFeedbackId(), dispatch.getWorkspaceId())
                .orElse(null);
        if (feedback == null || !matchesImmutablePackage(feedback, dispatch)) {
            return stateMachine.retry(
                    dispatch, owner, "Approved feedback is missing or no longer matches its immutable package");
        }
        // An approved package may consist of line notes alone; it then has no summary to look up or post.
        boolean hasSummary = !dispatch.getBody().isBlank();
        var inlineNotes = packageContent(dispatch).diffNotes();
        if (!hasSummary && inlineNotes.isEmpty()) {
            return stateMachine.refuse(dispatch, owner, FeedbackSuppressionReason.EMPTY_AFTER_SANITIZE);
        }
        @Nullable String summaryRef = dispatch.getDeliveredExternalRef();
        @Nullable String summaryUrl = dispatch.getDeliveredExternalUrl();
        List<DeliveredSignal> inlineSignals = deliveredSignals(dispatch);
        boolean writeBegan = false;
        try {
            if (hasSummary && summaryRef == null) {
                PullRequestCommentPoster.SummaryWrite write = summaryWrite(dispatch, job);
                ExistingDeliveryLookup existing = commentPoster.findExisting(write);
                if (existing.kind() == ExistingDeliveryLookup.Kind.UNKNOWN) {
                    return stateMachine.retry(dispatch, owner, "Provider lookup was inconclusive");
                }
                if (existing.kind() == ExistingDeliveryLookup.Kind.FOUND) {
                    summaryRef = Objects.requireNonNull(existing.commentId());
                    summaryUrl = existing.commentUrl();
                } else if (dispatch.getWriteStarted()) {
                    return stateMachine.retry(dispatch, owner, "A prior provider write has not been reconciled");
                } else {
                    PracticeFeedbackDeliveryPolicy.Decision<?> decision = evaluateAtEgress(dispatch, job);
                    if (!decision.allowed())
                        return refuseAfterReconciling(
                                dispatch,
                                job,
                                owner,
                                decision.refusal(),
                                dispatch.getDeliveredExternalRef(),
                                dispatch.getDeliveredExternalUrl(),
                                deliveredSignals(dispatch));
                    switch (PracticeFeedbackDeliveryPolicy.approvedRevision(feedback, job, decision)) {
                        case CHANGED -> {
                            return stateMachine.refuse(dispatch, owner, FeedbackSuppressionReason.APPROVAL_STALE);
                        }
                        case UNKNOWN -> {
                            return stateMachine.retry(dispatch, owner, REVISION_UNKNOWN);
                        }
                        case CURRENT -> {}
                    }
                    var reservation = stateMachine.reserve(
                            () -> lockedDecision(dispatch, job, feedback.getReviewedRevision()),
                            () -> repository.beginWrite(dispatch.getId(), dispatch.getWorkspaceId(), owner));
                    switch (reservation.status()) {
                        case REFUSED -> {
                            return refuseAfterReconciling(
                                    dispatch,
                                    job,
                                    owner,
                                    reservation.refusal(),
                                    dispatch.getDeliveredExternalRef(),
                                    dispatch.getDeliveredExternalUrl(),
                                    deliveredSignals(dispatch));
                        }
                        case STALE -> {
                            return stateMachine.refuse(dispatch, owner, FeedbackSuppressionReason.APPROVAL_STALE);
                        }
                        case UNKNOWN -> {
                            return stateMachine.retry(dispatch, owner, REVISION_UNKNOWN);
                        }
                        case LEASE_LOST -> {
                            return Result.inProgress();
                        }
                        case RESERVED -> {}
                    }
                    writeBegan = true;
                    SummaryHandle handle = commentPoster.post(write);
                    summaryRef = handle.externalId();
                    summaryUrl = handle.url();
                }
            }

            if (!inlineNotes.isEmpty()) {
                PracticeFeedbackDeliveryPolicy.Decision<?> decision = evaluateAtEgress(dispatch, job);
                if (!decision.allowed()) {
                    return refuseAfterReconciling(
                            dispatch, job, owner, decision.refusal(), summaryRef, summaryUrl, inlineSignals);
                }
                // With no inline write begun there is nothing to read back, so a stale approval is refused here.
                // Otherwise it is refused at each create, after the copies an earlier attempt may have made are read
                // back, so a write whose outcome is unknown is never settled unseen.
                if (!dispatch.inlineWriteMayHaveStarted()) {
                    switch (PracticeFeedbackDeliveryPolicy.approvedRevision(feedback, job, decision)) {
                        case CHANGED -> {
                            return stateMachine.refuse(
                                    dispatch,
                                    owner,
                                    FeedbackSuppressionReason.APPROVAL_STALE,
                                    summaryRef,
                                    summaryUrl,
                                    inlineSignals);
                        }
                        case UNKNOWN -> {
                            return stateMachine.retryPackage(
                                    dispatch, owner, REVISION_UNKNOWN, summaryRef, summaryUrl, inlineSignals);
                        }
                        case CURRENT -> {}
                    }
                }
                DiffNotePoster.DiffNoteResult inline = diffNotePoster.deliverPackage(
                        job,
                        InlinePackageScope.of(
                                dispatch, packageContent(dispatch).inlineMarker(), feedback.getReviewedRevision()),
                        inlineNotes,
                        inlineSignals,
                        () -> policy.currentReviewedRevision(job, feedback.getReviewedRevision()),
                        receipt -> stateMachine.recordInlineAttempt(
                                dispatch,
                                owner,
                                receipt,
                                () -> lockedDecision(dispatch, job, feedback.getReviewedRevision())));
                inlineSignals = inline.signals();
                if (inline.leaseLost()) return Result.inProgress();
                if (inline.refusalReason() != null && !inline.unconfirmed()) {
                    return stateMachine.refuse(
                            dispatch, owner, inline.refusalReason(), summaryRef, summaryUrl, inlineSignals);
                }
                if (inline.revisionChanged() && !inline.unconfirmed()) {
                    return stateMachine.refuse(
                            dispatch,
                            owner,
                            FeedbackSuppressionReason.APPROVAL_STALE,
                            summaryRef,
                            summaryUrl,
                            inlineSignals);
                }
                if (!inline.complete()) {
                    return inline.unconfirmed()
                            ? stateMachine.awaitUnconfirmed(dispatch, owner, summaryRef, summaryUrl, inlineSignals)
                            : stateMachine.retryPackage(
                                    dispatch,
                                    owner,
                                    "Approved review package remains incomplete",
                                    summaryRef,
                                    summaryUrl,
                                    inlineSignals);
                }
            }
            return stateMachine.sent(dispatch, owner, summaryRef, summaryUrl, inlineSignals);
        } catch (JobDeliverySuppressedException exception) {
            return stateMachine.refuse(
                    dispatch,
                    owner,
                    FeedbackSuppressionReason.INSTANCE_SILENCED,
                    summaryRef,
                    summaryUrl,
                    inlineSignals);
        } catch (PullRequestCommentPoster.SummaryNotSentException exception) {
            return retryUnsent(dispatch, owner, exception.getMessage());
        } catch (RuntimeException exception) {
            return stateMachine.retryFailedAttempt(
                    dispatch, owner, exception.getMessage(), writeBegan, summaryRef, summaryUrl, inlineSignals);
        }
    }

    /** The proposal as approved: its summary, absent when it is line notes alone, and the exact line notes. */
    boolean matchesImmutablePackage(Feedback feedback, FeedbackDispatch dispatch) {
        String body = feedback.getBody() == null ? "" : feedback.getBody();
        return body.equals(dispatch.getBody())
                && proposedInlineNotes(feedback).equals(packageContent(dispatch).diffNotes());
    }

    /**
     * Reopens only the fence this lease closed, and only because the channel proved the create request never left.
     * A create whose outcome is unknown keeps its fence: a marker lookup that finds nothing cannot tell a lost note
     * from one the provider has not made visible yet.
     */
    private Result retryUnsent(FeedbackDispatch dispatch, String owner, @Nullable String error) {
        Integer released = transactionTemplate.execute(
                status -> repository.releaseUnsentWrite(dispatch.getId(), dispatch.getWorkspaceId(), owner));
        if (released == null || released != 1) return stateMachine.retryAfterWrite(dispatch, owner, error);
        return stateMachine.retry(dispatch, owner, error, null, null, false, deliveredSignals(dispatch));
    }

    /**
     * Admits this claimed attempt only while no cited observation is invalidated. The live claim is what a
     * correction checks for, so once admitted the attempt runs to its end without a correction landing mid-write.
     */
    private boolean citesInvalidated(FeedbackDispatch dispatch) {
        List<UUID> cited = repository.lockCitedObservations(dispatch.getWorkspaceId(), dispatch.getId());
        return !cited.isEmpty()
                && !invalidations
                        .findActiveObservationIds(dispatch.getWorkspaceId(), cited)
                        .isEmpty();
    }

    /**
     * Withholds a dispatch once every write an earlier attempt started is
     * accounted for: the summary when its fence was set, inline notes when their own stage may have begun.
     * Only positive evidence settles a started write: an earlier POST may still land after its lease expired, so a
     * lookup that finds nothing proves nothing. The dispatch stays uncertain, claimable past the attempt budget by
     * that same write record, and keeps looking, more slowly once {@link #UNCONFIRMED_WINDOW} has passed since the
     * write began. Nothing is posted here.
     */
    private Result refuseAfterReconciling(
            FeedbackDispatch dispatch,
            AgentJob job,
            String owner,
            FeedbackSuppressionReason reason,
            @Nullable String summaryRef,
            @Nullable String summaryUrl,
            List<DeliveredSignal> signals) {
        @Nullable String resolvedSummaryRef = summaryRef;
        @Nullable String resolvedSummaryUrl = summaryUrl;
        List<DeliveredSignal> resolvedSignals = signals;
        boolean unconfirmed = false;
        if (resolvedSummaryRef == null
                && dispatch.getWriteStarted()
                && !dispatch.getBody().isBlank()) {
            ExistingDeliveryLookup existing;
            try {
                existing = commentPoster.findExisting(summaryWrite(dispatch, job));
            } catch (RuntimeException e) {
                existing = ExistingDeliveryLookup.unknown();
            }
            resolvedSummaryRef = existing.commentId();
            resolvedSummaryUrl = existing.commentUrl();
            unconfirmed = existing.kind() != ExistingDeliveryLookup.Kind.FOUND;
        }
        if (dispatch.inlineWriteMayHaveStarted() && !isIssue(job)) {
            DeliveryContent sealed = packageContent(dispatch);
            DiffNotePoster.InlineLookup lookup = diffNotePoster.findUnacknowledged(
                    job,
                    InlinePackageScope.recovered(dispatch, sealed.inlineMarker(), feedbackRepository),
                    sealed.diffNotes(),
                    resolvedSignals);
            resolvedSignals = stateMachine.mergeSignals(resolvedSignals, lookup.found());
            unconfirmed |= lookup.unconfirmed();
        }
        if (!unconfirmed) {
            return stateMachine.refuse(
                    dispatch, owner, reason, resolvedSummaryRef, resolvedSummaryUrl, resolvedSignals);
        }
        return stateMachine.awaitUnconfirmed(dispatch, owner, resolvedSummaryRef, resolvedSummaryUrl, resolvedSignals);
    }

    /**
     * Settles an attempt claimed past the budget without writing. An unconfirmed summary is looked up by its marker;
     * the package is sent only once every stage it has is accounted for, and fails otherwise.
     */
    private Result reconcileBeyondBudget(FeedbackDispatch dispatch, AgentJob job, String owner) {
        boolean summaryStage = !dispatch.getBody().isBlank();
        @Nullable String summaryRef = dispatch.getDeliveredExternalRef();
        @Nullable String summaryUrl = dispatch.getDeliveredExternalUrl();
        List<DeliveredSignal> signals = deliveredSignals(dispatch);
        if (summaryStage && summaryRef == null && dispatch.getWriteStarted()) {
            ExistingDeliveryLookup existing;
            try {
                existing = commentPoster.findExisting(summaryWrite(dispatch, job));
            } catch (RuntimeException e) {
                existing = ExistingDeliveryLookup.unknown();
            }
            if (existing.kind() != ExistingDeliveryLookup.Kind.FOUND) {
                return stateMachine.retry(dispatch, owner, "A prior provider write has not been reconciled");
            }
            summaryRef = existing.commentId();
            summaryUrl = existing.commentUrl();
        }
        boolean summaryAccounted = !summaryStage || summaryRef != null;
        boolean inlineAccounted = true;
        boolean inlineUnconfirmed = false;
        if (!isIssue(job)) {
            // The same sealed scope that posted the notes reads them back; it writes nothing.
            DeliveryContent sealed = packageContent(dispatch);
            DiffNotePoster.InlineLookup lookup = diffNotePoster.findUnacknowledged(
                    job,
                    InlinePackageScope.recovered(dispatch, sealed.inlineMarker(), feedbackRepository),
                    sealed.diffNotes(),
                    signals);
            signals = stateMachine.mergeSignals(signals, lookup.found());
            inlineAccounted = lookup.complete();
            inlineUnconfirmed = lookup.unconfirmed();
        }
        if (summaryAccounted
                && inlineAccounted
                && (summaryRef != null || signals.stream().anyMatch(DeliveredSignal::acknowledged))) {
            return stateMachine.sent(dispatch, owner, summaryRef, summaryUrl, signals);
        }
        if (inlineUnconfirmed) {
            return stateMachine.awaitUnconfirmed(dispatch, owner, summaryRef, summaryUrl, signals);
        }
        return stateMachine.retryPackage(
                dispatch, owner, "Dispatch retry limit exhausted", summaryRef, summaryUrl, signals);
    }

    private PracticeFeedbackDeliveryPolicy.Decision<PracticeFeedbackDeliveryPolicy.ReviewedRevision> lockedDecision(
            FeedbackDispatch dispatch, AgentJob job, @Nullable String proposalRevision) {
        var revision = policy.lockedReviewedRevision(job, proposalRevision);
        if (isIssue(job)) return PracticeFeedbackDeliveryPolicy.Decision.allowed(revision);
        var decision = evaluateAtEgress(dispatch, job);
        return decision.allowed()
                ? PracticeFeedbackDeliveryPolicy.Decision.allowed(revision)
                : PracticeFeedbackDeliveryPolicy.Decision.suppressed(decision.refusal());
    }

    private PracticeFeedbackDeliveryPolicy.Decision<?> evaluateAtEgress(FeedbackDispatch dispatch, AgentJob job) {
        Set<String> practiceSlugs = dispatch.getPracticeSlugs()
                .valueStream()
                .filter(JsonNode::isString)
                .map(JsonNode::asString)
                .collect(Collectors.toUnmodifiableSet());
        List<UUID> cited = Objects.requireNonNull(transactionTemplate.execute(
                status -> repository.lockCitedObservations(dispatch.getWorkspaceId(), dispatch.getId())));
        return policy.evaluateAtEgress(job, dispatch.getFeedbackId(), practiceSlugs, Set.copyOf(cited));
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
                        Objects.requireNonNull(placement.path()),
                        Objects.requireNonNull(placement.startLine()),
                        placement.endLine(),
                        placement.body(),
                        placement.deliveryKey(),
                        null))
                .toList();
    }

    /** The one routing decision for a package's summary: the reviewed work's own thread, under the package marker. */
    private PullRequestCommentPoster.SummaryWrite summaryWrite(FeedbackDispatch dispatch, AgentJob job) {
        String marker = dispatch.getDestination() == FeedbackDispatchDestination.APPROVED_REVIEW_PACKAGE
                ? PullRequestCommentPoster.approvedFeedbackMarker(dispatch.approvedFeedbackId())
                : PullRequestCommentPoster.summaryMarkerFor(job);
        return commentPoster.summaryWrite(job, isIssue(job), dispatch.getBody(), marker);
    }

    private static boolean isIssue(AgentJob job) {
        var artifact = AgentJobService.artifactKindFor(Objects.requireNonNull(job.getJobType()));
        if (artifact.equals(ArtifactKinds.ISSUE)) return true;
        if (artifact.equals(ArtifactKinds.PULL_REQUEST)) return false;
        throw new JobDeliveryException("Artifact dispatch does not support " + artifact.value());
    }

    private static @Nullable FeedbackSuppressionReason storedReason(FeedbackDispatch dispatch) {
        String stored = dispatch.getSuppressionReason();
        return stored == null ? null : FeedbackSuppressionReason.valueOf(stored);
    }

    Result recover(FeedbackDispatch dispatch, AgentJob job) {
        return personDataAdmission.deliver(job, () -> dispatch(dispatch, job));
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
            @Nullable String externalUrl,
            @Nullable FeedbackSuppressionReason suppressionReason,
            List<DeliveredSignal> deliveredSignals) {
        FeedbackSuppressionReason refusal() {
            return Objects.requireNonNull(suppressionReason, "a refused dispatch always names its reason");
        }

        String sentRef() {
            return Objects.requireNonNull(externalRef, "a sent dispatch always has a provider id");
        }

        /** Whether a copy is on the work: the summary, or a line note with a durable native handle. */
        boolean landed() {
            return externalRef != null || deliveredSignals.stream().anyMatch(DeliveredSignal::acknowledged);
        }

        static Result sent(@Nullable String ref) {
            return sent(ref, List.of());
        }

        static Result sent(@Nullable String ref, List<DeliveredSignal> signals) {
            return sent(ref, null, signals);
        }

        static Result sent(@Nullable String ref, @Nullable String url, List<DeliveredSignal> signals) {
            return new Result(Status.SENT, ref, url, null, List.copyOf(signals));
        }

        static Result suppressed(@Nullable FeedbackSuppressionReason reason) {
            return suppressed(reason, null);
        }

        static Result suppressed(@Nullable FeedbackSuppressionReason reason, @Nullable String ref) {
            return suppressed(reason, ref, List.of());
        }

        static Result suppressed(
                @Nullable FeedbackSuppressionReason reason, @Nullable String ref, List<DeliveredSignal> signals) {
            return suppressed(reason, ref, null, signals);
        }

        static Result suppressed(
                @Nullable FeedbackSuppressionReason reason,
                @Nullable String ref,
                @Nullable String url,
                List<DeliveredSignal> signals) {
            return new Result(Status.SUPPRESSED, ref, url, reason, List.copyOf(signals));
        }

        static Result uncertain(@Nullable String ref) {
            return uncertain(ref, null);
        }

        static Result uncertain(@Nullable String ref, @Nullable String url) {
            return uncertain(ref, url, List.of());
        }

        static Result uncertain(@Nullable String ref, @Nullable String url, List<DeliveredSignal> signals) {
            return new Result(Status.UNCERTAIN, ref, url, null, List.copyOf(signals));
        }

        static Result uncertain() {
            return uncertain(null);
        }

        static Result inProgress() {
            return new Result(Status.IN_PROGRESS, null, null, null, List.of());
        }

        static Result failed() {
            return failed(null, List.of());
        }

        static Result failed(@Nullable String ref) {
            return failed(ref, List.of());
        }

        static Result failed(@Nullable String ref, List<DeliveredSignal> signals) {
            return failed(ref, null, signals);
        }

        static Result failed(@Nullable String ref, @Nullable String url, List<DeliveredSignal> signals) {
            return new Result(Status.FAILED, ref, url, null, List.copyOf(signals));
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
