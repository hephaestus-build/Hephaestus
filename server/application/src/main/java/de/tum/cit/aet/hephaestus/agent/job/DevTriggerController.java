package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.handler.IssueReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.agent.handler.PullRequestReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobSubmissionRequest;
import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.review.GateDecision;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewDetectionGate;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-only REST endpoint for manually triggering PR/issue reviews. Enabled by
 * {@code hephaestus.dev.trigger-enabled=true}; gated additionally by {@code app_admin} since the property
 * only makes the endpoint exist, not an access control, and this route spends real LLM budget.
 *
 * <p>Two modes: <b>bypass</b> (no {@code signal}) submits directly, skipping the detection gate;
 * <b>gate-routed</b> (with {@code signal}) runs {@link PracticeReviewDetectionGate} first, exactly what the
 * production listener would do — the only way to validate RETROSPECTIVE (merged/closed) detection on a
 * SYNCED mirror, since real merge/close webhooks never arrive there.
 *
 * <p>{@link AgentJobService#submit} must not run inside an outer transaction, so the session-bound work is
 * confined to an explicit {@link TransactionTemplate} block and {@link AgentJobService#submitPrepared} runs
 * only after it commits.
 *
 * <pre>
 *   POST /api/dev/trigger-review?prId=...&amp;workspaceId=...                              (bypass)
 *   POST /api/dev/trigger-review?prId=...&amp;workspaceId=...&amp;signal=scm.pull_request.merged  (gate)
 *   POST /api/dev/trigger-review?issueId=...&amp;workspaceId=...&amp;signal=scm.issue.closed      (gate)
 * </pre>
 */
@RestController
@ConditionalOnProperty(name = "hephaestus.dev.trigger-enabled", havingValue = "true")
@RecentSignInExempt(reason = "development-only trigger, disabled unless hephaestus.dev.trigger-enabled is set")
@PreAuthorize("hasAuthority('app_admin')")
@WorkspaceAgnostic("Dev-only endpoint; workspace ID passed as request parameter")
public class DevTriggerController {

    private final AgentJobService agentJobService;
    private final ReviewableArtifactLoader artifactLoader;
    private final PracticeReviewDetectionGate detectionGate;
    private final TransactionTemplate transactionTemplate;
    private final SignalRecorder signalRecorder;

    public DevTriggerController(
            AgentJobService agentJobService,
            ReviewableArtifactLoader artifactLoader,
            PracticeReviewDetectionGate detectionGate,
            TransactionTemplate transactionTemplate,
            SignalRecorder signalRecorder) {
        this.agentJobService = agentJobService;
        this.artifactLoader = artifactLoader;
        this.detectionGate = detectionGate;
        this.transactionTemplate = transactionTemplate;
        this.signalRecorder = signalRecorder;
    }

    /**
     * Outcome of the session-bound prep phase: a request ready to submit, or a terminal message.
     *
     * @param signalKey the ledger identity this run is recorded under; present only in bypass mode
     */
    private record Prepared(
            @Nullable AgentJobType jobType,
            @Nullable Object request,
            @Nullable String message,
            @Nullable SignalKey signalKey) {
        static Prepared review(@Nullable PullRequestReviewSubmissionRequest request, @Nullable SignalKey key) {
            return new Prepared(AgentJobType.PULL_REQUEST_REVIEW, request, null, key);
        }

        static Prepared issue(@Nullable IssueReviewSubmissionRequest request, @Nullable SignalKey key) {
            return new Prepared(AgentJobType.ISSUE_REVIEW, request, null, key);
        }

        static Prepared done(String message) {
            return new Prepared(null, null, message, null);
        }
    }

    @PostMapping("/api/dev/trigger-review")
    @AuditExempt(
            reason = "submits a review job; changes no configuration or access, and the run and its spend are "
                    + "already recorded on agent_job and the LLM usage ledger")
    public String triggerReview(
            @RequestParam @Nullable Long prId,
            @RequestParam @Nullable Long issueId,
            @RequestParam @Nullable Long workspaceId,
            @RequestParam @Nullable String signal) {
        if (workspaceId == null || (prId == null && issueId == null)) {
            return "Error: workspaceId and one of prId / issueId are required";
        }

        Prepared prepared = transactionTemplate.execute(status -> issueId != null
                ? prepareIssue(workspaceId, issueId, signal)
                : preparePullRequest(workspaceId, Objects.requireNonNull(prId), signal));

        if (prepared == null || prepared.request() == null) {
            return prepared == null
                    ? "No submission prepared"
                    : Objects.requireNonNullElse(prepared.message(), "No submission prepared");
        }
        return agentJobService.submitPrepared(
                workspaceId,
                Objects.requireNonNull(prepared.jobType()),
                (JobSubmissionRequest) prepared.request(),
                prepared.signalKey());
    }

    private Prepared preparePullRequest(Long workspaceId, Long prId, @Nullable String signal) {
        PullRequest pr =
                artifactLoader.findPullRequestForGate(workspaceId, prId).orElse(null);
        if (pr == null) {
            return Prepared.done("PR not found in workspace " + workspaceId + ": " + prId);
        }
        SignalName triggerSignal = signal == null || signal.isBlank() ? null : SignalName.of(signal);
        if (triggerSignal != null) {
            GateDecision decision = detectionGate.evaluate(pr, triggerSignal, TriggerMode.AUTO);
            if (decision instanceof GateDecision.Skip skip) {
                recordRefusal(
                        ScmSignals.pullRequestKey(
                                        workspaceId,
                                        pr.getId(),
                                        triggerSignal,
                                        pr.getHeadRefOid(),
                                        pr.getTitle(),
                                        pr.getBody())
                                .orElse(null),
                        skip);
                return Prepared.done("Gate skipped (" + triggerSignal + "): " + skip.reason());
            }
        }
        PullRequestReviewSubmissionRequest request = agentJobService.buildReviewRequest(pr, triggerSignal);
        return request == null
                ? Prepared.done("PR missing branch info: prId=" + pr.getId())
                : Prepared.review(
                        request,
                        triggerSignal == null
                                ? recordManualRequest(workspaceId, pr.getId(), ScmSignals.PULL_REQUEST_MANUAL_REVIEW)
                                : null);
    }

    private Prepared prepareIssue(Long workspaceId, Long issueId, @Nullable String signal) {
        Issue issue = artifactLoader.findIssueForGate(workspaceId, issueId).orElse(null);
        if (issue == null) {
            return Prepared.done("Issue not found in workspace " + workspaceId + ": " + issueId);
        }
        SignalName triggerSignal = signal == null || signal.isBlank() ? null : SignalName.of(signal);
        // Before the gate, because an issue whose repository the mirror never resolved is not
        // reviewable at all: reading one to build a signal key would dereference that relation, and a
        // refusal recorded against it would claim the gate had an opinion about an artifact nobody can
        // fetch.
        IssueReviewSubmissionRequest request = agentJobService.buildIssueRequest(issue, triggerSignal);
        if (request == null) {
            return Prepared.done("Issue missing repository: issueId=" + issue.getId());
        }
        if (triggerSignal != null) {
            GateDecision decision = detectionGate.evaluateIssue(issue, triggerSignal, TriggerMode.AUTO);
            if (decision instanceof GateDecision.Skip skip) {
                recordRefusal(
                        ScmSignals.issueKey(workspaceId, triggerSignal, ScmEventPayload.IssueData.from(issue))
                                .orElse(null),
                        skip);
                return Prepared.done("Gate skipped (" + triggerSignal + "): " + skip.reason());
            }
        }
        return Prepared.issue(
                request,
                triggerSignal == null
                        ? recordManualRequest(workspaceId, issue.getId(), ScmSignals.ISSUE_MANUAL_REVIEW)
                        : null);
    }

    /**
     * Opens a ledger entry for a run this endpoint occasioned itself, and hands back its key. A fresh run id
     * per call: an ask is its own occasion, not deduplicated against another one.
     */
    private SignalKey recordManualRequest(long workspaceId, long artifactId, SignalName requestSignal) {
        SignalKey key = ScmSignals.manualKey(workspaceId, artifactId, requestSignal, UUID.randomUUID());
        signalRecorder.record(key, Instant.now(), DiscoveredVia.MANUAL);
        return key;
    }

    /**
     * Settles the ledger the way the production listener does, so a gate-routed trigger leaves the same trace
     * a real delivery would. Null key when the signal has nothing stable to be keyed on yet.
     */
    private void recordRefusal(@Nullable SignalKey key, GateDecision.Skip skip) {
        if (key == null) {
            return;
        }
        signalRecorder.markRefused(key, skip.resolvedSignalReason());
    }
}
