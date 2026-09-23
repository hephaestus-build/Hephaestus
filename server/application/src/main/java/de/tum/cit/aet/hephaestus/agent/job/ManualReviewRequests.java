package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.handler.IssueReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.agent.handler.PullRequestReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobSubmissionRequest;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.PracticeSignalOptions;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.review.GateDecision;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewDetectionGate;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one path a review somebody asked for takes, whether through the {@code /hephaestus review} command
 * or the "Review this now" REST endpoint: check standing (see {@link ReviewRequestAuthority}), ask the
 * gate, record the ask in the signal ledger, submit, and return a refusal a person can read.
 *
 * <p>The gate is asked about the kind's declared manual-request signal, not a lifecycle event that did not
 * happen — the artifact trace renders the signal as the reason a review ran.
 *
 * <p>The job's metadata carries no trigger signal: a job with none runs every active practice of the
 * artifact's kind, which is what "review this now" means. The ledger key still names the request signal.
 *
 * <p>{@link ObservationOrigin#MANUAL} is passed explicitly rather than left to the submission request's
 * default, which reads the trigger signal and would misreport this as an unbounded organic signal.
 *
 * <p>Checks run standing, then rate limits, then the ledger row, then the gate. Limiting before recording
 * keeps a declined ask from tightening the limit under retry (see {@link ManualReviewRateLimits}); the
 * gate runs last because only it needs a ledger row to settle against.
 *
 * <p>Work the mirror has tombstoned is refused here as {@link SignalStateReason#ARTIFACT_GONE}, not by the
 * ownership check: the workspace does monitor that artifact, so ownership answers yes, and an asker who is
 * looking straight at the work is better served by a reason they can read than by a 404. Terminal is right
 * here and wrong for an automatically re-offered signal, which the resubmitters hold under
 * {@link SignalStateReason#ARTIFACT_NOT_VISIBLE} instead: nobody is waiting on this answer, so a tombstone
 * a later sync reverses must not have retired the occasion. ADR 0024 § Update — 2026-09-04 (issue #1806):
 * a re-offered signal is held, not retired.
 */
@Service
public class ManualReviewRequests {

    private static final Logger log = LoggerFactory.getLogger(ManualReviewRequests.class);

    private final ReviewRequestAuthority authority;
    private final ManualReviewRateLimits rateLimits;
    private final PracticeReviewDetectionGate gate;
    private final PracticeSignalOptions signalOptions;
    private final SignalRecorder signalRecorder;
    private final AgentJobService agentJobService;
    private final TransactionTemplate transactionTemplate;

    public ManualReviewRequests(
            ReviewRequestAuthority authority,
            ManualReviewRateLimits rateLimits,
            PracticeReviewDetectionGate gate,
            PracticeSignalOptions signalOptions,
            SignalRecorder signalRecorder,
            AgentJobService agentJobService,
            TransactionTemplate transactionTemplate) {
        this.authority = authority;
        this.rateLimits = rateLimits;
        this.gate = gate;
        this.signalOptions = signalOptions;
        this.signalRecorder = signalRecorder;
        this.agentJobService = agentJobService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * <strong>Must not be called inside a transaction</strong> — {@link AgentJobService#submit} opens its
     * own. The pull request's associations (author, assignees, repository, branch refs) must already be
     * fetched.
     *
     * @param requesters every SCM identity of the person asking; asking only one risks refusing an admin
     *     who signed in through a different provider. Empty means nobody was identified.
     */
    public ManualReviewOutcome requestPullRequestReview(
            Workspace workspace, PullRequest pullRequest, Collection<User> requesters) {
        User requester =
                authority.standingOf(workspace.getId(), pullRequest, requesters).orElse(null);
        if (requester == null) {
            log.info(
                    "Manual review request refused: no standing on the artifact, workspaceId={}, prId={}, identities={}",
                    workspace.getId(),
                    pullRequest.getId(),
                    requesters.size());
            return ManualReviewOutcome.forbidden();
        }
        if (pullRequest.getDeletedAt() != null) {
            return ManualReviewOutcome.refused(SignalStateReason.ARTIFACT_GONE);
        }
        String headRefOid = pullRequest.getHeadRefOid();
        if (headRefOid == null || pullRequest.getHeadRefName() == null || pullRequest.getBaseRefName() == null) {
            // Reported as the artifact being gone, not a gate skip: the mirror hasn't caught up, the
            // workspace didn't decline anything.
            return ManualReviewOutcome.refused(SignalStateReason.ARTIFACT_GONE);
        }
        return run(
                workspace,
                pullRequest,
                new Asker(requester, identityIds(requesters)),
                signal -> gate.evaluate(pullRequest, signal, TriggerMode.MANUAL),
                signal -> gate.evaluateAdministrative(pullRequest, signal),
                AgentJobType.PULL_REQUEST_REVIEW,
                () -> new PullRequestReviewSubmissionRequest(
                        ScmEventPayload.PullRequestData.from(pullRequest),
                        pullRequest.getHeadRefName(),
                        headRefOid,
                        pullRequest.getBaseRefName(),
                        pullRequest.getBaseRefOid(),
                        null,
                        ObservationOrigin.MANUAL));
    }

    /** Issue-shaped counterpart of {@link #requestPullRequestReview}, with the same preconditions. */
    public ManualReviewOutcome requestIssueReview(Workspace workspace, Issue issue, Collection<User> requesters) {
        User requester =
                authority.standingOf(workspace.getId(), issue, requesters).orElse(null);
        if (requester == null) {
            log.info(
                    "Manual review request refused: no standing on the artifact, workspaceId={}, issueId={}, identities={}",
                    workspace.getId(),
                    issue.getId(),
                    requesters.size());
            return ManualReviewOutcome.forbidden();
        }
        if (issue.getDeletedAt() != null) {
            return ManualReviewOutcome.refused(SignalStateReason.ARTIFACT_GONE);
        }
        if (issue.getRepository() == null) {
            return ManualReviewOutcome.refused(SignalStateReason.ARTIFACT_GONE);
        }
        return run(
                workspace,
                issue,
                new Asker(requester, identityIds(requesters)),
                signal -> gate.evaluateIssue(issue, signal, TriggerMode.MANUAL),
                signal -> gate.evaluateIssueAdministrative(issue, signal),
                AgentJobType.ISSUE_REVIEW,
                () -> new IssueReviewSubmissionRequest(
                        issue.getId(),
                        issue.getNumber(),
                        issue.requireRepository().getId(),
                        issue.requireRepository().getNameWithOwner(),
                        issue.getTitle(),
                        issue.getBody() != null ? issue.getBody() : "",
                        issue.getState() != null ? issue.getState().name() : "OPEN",
                        issue.getHtmlUrl(),
                        issue.getUpdatedAt(),
                        null,
                        ObservationOrigin.MANUAL));
    }

    /**
     * The person asking, as both halves of what a request needs to know about them.
     *
     * @param standing the identity the authority accepted, and the one the ledger row is filed under
     * @param identityIds every identity of that same person, which is what the hourly allowance counts —
     *     counting only the accepted one would hand a linked account one allowance per provider
     */
    private record Asker(User standing, List<Long> identityIds) {}

    private ManualReviewOutcome run(
            Workspace workspace,
            Issue artifact,
            Asker asker,
            Function<SignalName, GateDecision> evaluate,
            Function<SignalName, GateDecision> evaluateAdministrative,
            AgentJobType jobType,
            Supplier<JobSubmissionRequest> submission) {
        ArtifactKind kind = AgentJobService.artifactKindFor(jobType);
        SignalName requestSignal = signalOptions.manualRequestSignalFor(kind).orElse(null);
        if (requestSignal == null) {
            // Refusing beats inventing a signal name the descriptor does not know.
            log.warn("Manual review request on a kind that declares none: kind={}", kind);
            return ManualReviewOutcome.refused(SignalStateReason.NO_ACTIVE_PRACTICE);
        }

        SignalStateReason limited = rateLimits
                .refusalFor(workspace, kind, artifact.getId(), asker.identityIds())
                .orElse(null);
        if (limited != null) {
            log.info(
                    "Manual review request: rate limited, workspaceId={}, artifactId={}, requesterId={}, reason={}",
                    workspace.getId(),
                    artifact.getId(),
                    asker.standing().getId(),
                    limited);
            return ManualReviewOutcome.refused(limited);
        }

        // A fresh run id per ask keeps two people asking as two occasions instead of one deduplicating
        // the other, and keeps a request from consuming the ledger entry an ordinary event would use.
        SignalKey key = ScmSignals.manualKey(workspace.getId(), artifact.getId(), requestSignal, UUID.randomUUID());
        transactionTemplate.executeWithoutResult(status -> signalRecorder.record(
                key, Instant.now(), DiscoveredVia.MANUAL, asker.standing().getId()));

        GateDecision decision = evaluate.apply(requestSignal);
        if (decision instanceof GateDecision.Skip skip
                && isCoverageRefusal(skip.resolvedSignalReason())
                && authority.isWorkspaceAdmin(
                        workspace.getId(), asker.standing().getId())) {
            decision = evaluateAdministrative.apply(requestSignal);
        }
        if (decision instanceof GateDecision.Skip skip) {
            log.info(
                    "Manual review request: gate declined, workspaceId={}, artifactId={}, reason={}",
                    workspace.getId(),
                    artifact.getId(),
                    skip.reason());
            SignalStateReason reason = skip.resolvedSignalReason();
            transactionTemplate.executeWithoutResult(status -> signalRecorder.markRefused(key, reason));
            return ManualReviewOutcome.refused(reason);
        }

        SubmissionOutcome outcome = agentJobService.submitWithOutcome(
                workspace.getId(), jobType, submission.get(), key, (GateDecision.Detect) decision);
        if (outcome.job() == null) {
            return ManualReviewOutcome.refused(outcome.requireRefusal());
        }
        log.info(
                "Manual review request: submitted, jobId={}, workspaceId={}, artifactId={}, requesterId={}",
                outcome.job().getId(),
                workspace.getId(),
                artifact.getId(),
                asker.standing().getId());
        return ManualReviewOutcome.submitted(outcome.job().getId());
    }

    /** Ids only: the limit counts rows, and a detached {@link User} is more than it needs to hold. */
    private static List<Long> identityIds(Collection<User> requesters) {
        return requesters.stream().map(User::getId).filter(Objects::nonNull).toList();
    }

    private static boolean isCoverageRefusal(SignalStateReason reason) {
        return reason == SignalStateReason.OUT_OF_REVIEW_SCOPE || reason == SignalStateReason.SUBJECT_UNLINKED;
    }
}
