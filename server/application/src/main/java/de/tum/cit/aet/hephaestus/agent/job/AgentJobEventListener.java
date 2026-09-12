package de.tum.cit.aet.hephaestus.agent.job;

import static de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent.TriggerEventNames;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.handler.PullRequestReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewSubject;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.review.GateDecision;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewDetectionGate;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceResolver;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens for PR and review domain events, records what was observed, and submits practice-aware
 * agent jobs for the observations that warrant one.
 *
 * <p>Uses {@code @Async @TransactionalEventListener(AFTER_COMMIT)} to avoid blocking the webhook
 * processing thread and to ensure entities are committed before we read them.
 *
 * <p><strong>How a transition was discovered does not decide whether it is recorded.</strong> Dropping a
 * reconciliation-sourced event at the door would leave a successfully received transition with no trace
 * anywhere. The source governs only whether a review is <em>triggered</em>; both sources reach the ledger.
 */
@Component
// Ledger rows this listener writes are only ever drained by the server-role PendingSignalReaper;
// recording one anywhere else would queue work nothing consumes.
@ConditionalOnServerRole
@ConditionalOnProperty(prefix = "hephaestus.agent", name = "enabled", havingValue = "true")
public class AgentJobEventListener {

    private static final Logger log = LoggerFactory.getLogger(AgentJobEventListener.class);

    private final AgentJobService agentJobService;
    private final PullRequestRepository pullRequestRepository;
    private final PracticeReviewDetectionGate practiceReviewDetectionGate;
    private final WorkspaceResolver workspaceResolver;
    private final SignalRecorder signalRecorder;

    public AgentJobEventListener(
            AgentJobService agentJobService,
            PullRequestRepository pullRequestRepository,
            PracticeReviewDetectionGate practiceReviewDetectionGate,
            WorkspaceResolver workspaceResolver,
            SignalRecorder signalRecorder) {
        this.agentJobService = agentJobService;
        this.pullRequestRepository = pullRequestRepository;
        this.practiceReviewDetectionGate = practiceReviewDetectionGate;
        this.workspaceResolver = workspaceResolver;
        this.signalRecorder = signalRecorder;
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPullRequestCreated(ScmDomainEvent.PullRequestCreated event) {
        handlePullRequestEvent(event.pullRequest(), event.context(), TriggerEventNames.PULL_REQUEST_CREATED);
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPullRequestReady(ScmDomainEvent.PullRequestReady event) {
        handlePullRequestEvent(event.pullRequest(), event.context(), TriggerEventNames.PULL_REQUEST_READY);
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPullRequestSynchronized(ScmDomainEvent.PullRequestSynchronized event) {
        handlePullRequestEvent(event.pullRequest(), event.context(), TriggerEventNames.PULL_REQUEST_SYNCHRONIZED);
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewSubmitted(ScmDomainEvent.ReviewSubmitted event) {
        handleReviewEvent(event.review(), event.context());
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPullRequestMerged(ScmDomainEvent.PullRequestMerged event) {
        // Deliberately without the closed/merged short-circuit: here the terminal state IS the
        // trigger's reason to run.
        dispatch(event.pullRequest(), event.context(), TriggerEventNames.PULL_REQUEST_MERGED, null);
    }

    /**
     * A merge publishes BOTH {@code PullRequestClosed(wasMerged=true)} and {@code PullRequestMerged}, so
     * this handler routes only the abandoned-close case and leaves the landing to
     * {@link #onPullRequestMerged}.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPullRequestClosed(ScmDomainEvent.PullRequestClosed event) {
        if (event.wasMerged()) {
            return;
        }
        dispatch(event.pullRequest(), event.context(), TriggerEventNames.PULL_REQUEST_CLOSED, null);
    }

    // PR event handling

    private void handlePullRequestEvent(
            ScmEventPayload.PullRequestData prData, EventContext context, String triggerEventName) {
        if (isClosedOrMerged(prData.state(), prData.isMerged())) {
            return;
        }
        dispatch(prData, context, triggerEventName, null);
    }

    /**
     * Records the observation, then decides whether it warrants a review.
     *
     * <p>The ledger settles the first question for everyone: whichever observer of an occurrence gets
     * there first is told to act, and the rest stop here. That is what makes deduplication outlast the
     * job, so a webhook redelivered after the review completed no longer re-runs it.
     */
    private void dispatch(
            ScmEventPayload.PullRequestData prData,
            EventContext context,
            String triggerEventName,
            ScmEventPayload.@Nullable ReviewData reviewData) {
        try {
            SignalKey key = signalKeyFor(prData, triggerEventName, reviewData);
            if (key == null) {
                return;
            }

            DiscoveredVia discoveredVia = context.isSync() ? DiscoveredVia.SYNC : DiscoveredVia.EVENT;
            if (!signalRecorder.record(key, context.occurredAt(), discoveredVia)) {
                log.debug(
                        "Signal already settled, not reviewing again: prNumber={}, repoName={}, signal={}",
                        prData.number(),
                        repositoryNameOf(prData),
                        key.signalName());
                return;
            }

            // Reconciliation establishes THAT a transition happened, not live coaching about it — the
            // row it leaves behind is what a later live delivery can still claim.
            if (context.isSync()) {
                return;
            }

            PullRequest pr =
                    pullRequestRepository.findByIdWithAllForGate(prData.id()).orElse(null);
            if (pr == null) {
                log.warn("Cannot submit agent job: PR not found, prId={}", prData.id());
                signalRecorder.markRefused(key, SignalStateReason.ARTIFACT_GONE);
                return;
            }

            // A tombstone the next ordinary sync can lift, so the occasion is held rather than retired:
            // the reaper re-offers it, and a sweep that guessed wrong costs a delay instead of a review.
            if (pr.getDeletedAt() != null) {
                signalRecorder.markRefused(key, SignalStateReason.ARTIFACT_NOT_VISIBLE);
                return;
            }

            // A merged PR keeps its stored refs, so this holds post-merge too: without them there is
            // nothing to clone or diff against. Left RECORDED rather than refused on purpose: no
            // decision was reached, so a later delivery of the same occurrence can still claim it once
            // the mirror has the refs.
            if (!hasBranchInfo(pr, prData.id())) {
                return;
            }

            GateDecision decision = reviewData == null
                    ? practiceReviewDetectionGate.evaluate(pr, key.signalName(), TriggerMode.AUTO)
                    : practiceReviewDetectionGate.evaluate(
                            pr, key.signalName(), TriggerMode.AUTO, new ReviewSubject(reviewData.authorId(), true));
            switch (decision) {
                case GateDecision.Skip skip -> {
                    log.debug(
                            "Agent job skipped by practice gate: prNumber={}, repoName={}, event={}, reason={}",
                            prData.number(),
                            repositoryNameOf(prData),
                            triggerEventName,
                            skip.reason());
                    signalRecorder.markRefused(key, skip.resolvedSignalReason());
                }
                case GateDecision.Detect detect -> submitJob(prData, pr, detect, key, reviewData);
            }
        } catch (Exception e) {
            log.error(
                    "Failed to process PR event: prNumber={}, repoName={}, event={}",
                    prData.number(),
                    repositoryNameOf(prData),
                    triggerEventName,
                    e);
        }
    }

    // Review event handling

    private void handleReviewEvent(ScmEventPayload.ReviewData reviewData, EventContext context) {
        try {
            // ReviewSubmitted carries ReviewData, not PullRequestData, so the PR is loaded here.
            PullRequest pr = pullRequestRepository
                    .findByIdWithAllForGate(reviewData.pullRequestId())
                    .orElse(null);
            if (pr == null) {
                log.warn(
                        "Cannot submit agent job for review: PR not found, reviewId={}, pullRequestId={}",
                        reviewData.id(),
                        reviewData.pullRequestId());
                return;
            }

            // Reviews can arrive on already-merged PRs (drive-by reviews).
            if (isClosedOrMerged(pr.getState(), pr.isMerged())) {
                return;
            }

            if (reviewData.authorId() == null) {
                log.warn("Cannot submit agent job for review without an author: reviewId={}", reviewData.id());
                return;
            }
            dispatch(ScmEventPayload.PullRequestData.from(pr), context, TriggerEventNames.REVIEW_SUBMITTED, reviewData);
        } catch (Exception e) {
            log.error(
                    "Failed to process review event: reviewId={}, pullRequestId={}",
                    reviewData.id(),
                    reviewData.pullRequestId(),
                    e);
        }
    }

    // Shared helpers

    /**
     * The ledger identity of this event, or null when there is nothing stable to key it on yet.
     *
     * <p>Reads the head commit through a single-column projection rather than the gate's fetch graph:
     * a reconciliation pass records without ever needing the rest of it.
     */
    private @Nullable SignalKey signalKeyFor(
            ScmEventPayload.PullRequestData prData,
            String triggerEventName,
            ScmEventPayload.@Nullable ReviewData reviewData) {
        // The mirror can hold a pull request whose repository row is gone; that reads as "not ours"
        // rather than as an error, matching the gate.
        String repositoryName = repositoryNameOf(prData);
        Workspace workspace =
                workspaceResolver.resolveForRepository(repositoryName).orElse(null);
        if (workspace == null) {
            log.debug("No workspace owns this repository, nothing to record: repoName={}", repositoryName);
            return null;
        }
        SignalName signal = ScmSignals.forTriggerEvent(triggerEventName).orElse(null);
        if (signal == null) {
            log.debug("No signal declared for trigger event, nothing to record: event={}", triggerEventName);
            return null;
        }
        if (signal.equals(ScmSignals.PULL_REQUEST_REVIEWED)) {
            return reviewData == null
                    ? null
                    : ScmSignals.pullRequestReviewKey(workspace.getId(), prData.id(), reviewData.id());
        }
        return ScmSignals.pullRequestKey(
                        workspace.getId(),
                        prData.id(),
                        signal,
                        pullRequestRepository.findHeadRefOidById(prData.id()).orElse(null),
                        prData.title(),
                        prData.body())
                .orElse(null);
    }

    private static @Nullable String repositoryNameOf(ScmEventPayload.PullRequestData prData) {
        return prData.repository() != null ? prData.repository().nameWithOwner() : null;
    }

    private static boolean isClosedOrMerged(Issue.State state, boolean merged) {
        return state == Issue.State.CLOSED || state == Issue.State.MERGED || merged;
    }

    private boolean hasBranchInfo(PullRequest pr, Long prId) {
        if (pr.getHeadRefOid() == null || pr.getHeadRefName() == null || pr.getBaseRefName() == null) {
            log.warn(
                    "Cannot submit agent job: missing branch info, prId={}, headRefOid={}, headRefName={}, baseRefName={}",
                    prId,
                    pr.getHeadRefOid(),
                    pr.getHeadRefName(),
                    pr.getBaseRefName());
            return false;
        }
        return true;
    }

    private void submitJob(
            ScmEventPayload.PullRequestData prData,
            PullRequest pr,
            GateDecision.Detect detect,
            SignalKey signalKey,
            ScmEventPayload.@Nullable ReviewData reviewData) {
        String headRefOid = pr.getHeadRefOid();
        if (headRefOid == null) {
            log.warn("Cannot submit agent job: missing head commit, prId={}", pr.getId());
            return;
        }
        PullRequestReviewSubmissionRequest request = new PullRequestReviewSubmissionRequest(
                prData,
                pr.getHeadRefName(),
                headRefOid,
                pr.getBaseRefName(),
                pr.getBaseRefOid(),
                signalKey.signalName());
        if (reviewData != null) request = request.forSubmittedReview(reviewData);

        try {
            agentJobService
                    .submit(detect.workspace().getId(), AgentJobType.PULL_REQUEST_REVIEW, request, signalKey, detect)
                    .ifPresent(job -> log.info(
                            "Agent job submitted: jobId={}, prNumber={}, repoName={}, signal={}, matchedPractices={}",
                            job.getId(),
                            prData.number(),
                            repositoryNameOf(prData),
                            signalKey.signalName(),
                            detect.matchedPractices().size()));
        } catch (Exception e) {
            log.error(
                    "Failed to submit agent job: prNumber={}, repoName={}, signal={}",
                    prData.number(),
                    repositoryNameOf(prData),
                    signalKey.signalName(),
                    e);
        }
    }
}
