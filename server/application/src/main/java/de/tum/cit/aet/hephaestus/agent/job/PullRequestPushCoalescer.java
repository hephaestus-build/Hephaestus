package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.context.providers.LinkedWorkItemContentSource;
import de.tum.cit.aet.hephaestus.agent.handler.ReplaceableReviewCoverage;
import de.tum.cit.aet.hephaestus.core.TransactionCallbacks;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignal;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignalRepository;
import de.tum.cit.aet.hephaestus.integration.core.signal.PendingSignalResubmitter;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalState;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Coalesces pushes and title or description edits into one review of the work as it stands after
 * {@link #QUIET_PERIOD}, or {@link #MAX_WAIT} from the first of them. The workspace cooldown defers the
 * group instead of refusing it. A push in the group makes it a push review; edits alone are an edit review.
 *
 * <p>Also the pending-signal reaper's way back in for a pull request: a push or edit admission held back is
 * re-offered through the same settlement, so it waits for newer edits and pushes as the sweep does and
 * cannot review the work ahead of them. Every other pull-request occasion goes straight to the submitter.
 *
 * <p>A review admitted here can wait in the queue while the work moves on and a newer one is admitted. The sweep then
 * completes each queued one the newest covers as {@link ReviewRunOutcome#SUPERSEDED} instead of starting its next
 * attempt ({@link #replaceCovered(long, long)}).
 */
@Component
@ConditionalOnServerRole
@ConditionalOnBooleanProperty(RuntimeRole.AGENT_ENABLED_PROPERTY)
@WorkspaceAgnostic("Discovers due push occasions across workspaces; each drain is workspace-scoped")
public class PullRequestPushCoalescer implements PendingSignalResubmitter {

    static final Duration QUIET_PERIOD = Duration.ofMinutes(10);
    static final Duration MAX_WAIT = Duration.ofMinutes(60);
    private static final int BATCH_SIZE = 100;
    /**
     * The occasions that revise the work, its code or its description: they settle together here, and a review of
     * one also rechecks the problems the revision may have answered.
     */
    static final List<SignalName> SIGNALS = List.of(
            ScmSignals.PULL_REQUEST_SYNCHRONIZED,
            ScmSignals.PULL_REQUEST_EDITED,
            ScmSignals.PULL_REQUEST_LINKED_ISSUE_UPDATED);

    private static final Logger log = LoggerFactory.getLogger(PullRequestPushCoalescer.class);

    private final ArtifactSignalRepository signals;
    private final PullRequestRepository pullRequests;
    private final SignalRecorder recorder;
    private final PullRequestSignalResubmitter submitter;
    private final WorkspaceResolver workspaceResolver;
    private final PracticeReviewProperties reviewProperties;
    private final AgentJobRepository jobs;
    private final TransactionTemplate transactions;
    private final ReplaceableReviewCoverage coverage;
    private final AgentJobTelemetry telemetry;
    private final JsonMapper mapper;
    // Where the next replacement pass resumes, by workspace and pull request id. Sweeps are serialized by their lock.
    private long replacementCursorWorkspace;
    private long replacementCursorPullRequest;

    public PullRequestPushCoalescer(
            ArtifactSignalRepository signals,
            PullRequestRepository pullRequests,
            SignalRecorder recorder,
            PullRequestSignalResubmitter submitter,
            WorkspaceResolver workspaceResolver,
            PracticeReviewProperties reviewProperties,
            AgentJobRepository jobs,
            TransactionTemplate transactions,
            ReplaceableReviewCoverage coverage,
            AgentJobTelemetry telemetry,
            JsonMapper mapper) {
        this.signals = signals;
        this.pullRequests = pullRequests;
        this.recorder = recorder;
        this.submitter = submitter;
        this.workspaceResolver = workspaceResolver;
        this.reviewProperties = reviewProperties;
        this.jobs = jobs;
        this.transactions = transactions;
        this.coverage = coverage;
        this.telemetry = telemetry;
        this.mapper = mapper;
    }

    @Override
    public ArtifactKind artifactKind() {
        return ScmSignals.PULL_REQUEST;
    }

    @Override
    public void resubmit(ArtifactSignal signal) {
        SignalKey key = signal.key();
        if (SIGNALS.contains(key.signalName())) {
            transactions.executeWithoutResult(status -> drain(key.workspaceId(), key.artifactId(), Instant.now()));
        } else {
            submitter.resubmit(signal);
        }
    }

    /** Locked for the same reason as {@link IssueUpdateCoalescer#sweep}. */
    @Scheduled(fixedDelay = 30, initialDelay = 30, timeUnit = TimeUnit.SECONDS)
    @SchedulerLock(name = "pull-request-push-coalescer", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
    public void sweep() {
        for (SignalName signal : SIGNALS) {
            Instant queryNow = Instant.now();
            for (var artifact : signals.findDueDeferred(
                    signal.value(), queryNow.minus(QUIET_PERIOD), queryNow.minus(MAX_WAIT), BATCH_SIZE)) {
                try {
                    Instant now = Instant.now();
                    signals.noteDeferredAttempt(
                            artifact.getWorkspaceId(), artifact.getArtifactId(), signal.value(), now);
                    transactions.executeWithoutResult(
                            status -> drain(artifact.getWorkspaceId(), artifact.getArtifactId(), now));
                } catch (RuntimeException e) {
                    log.warn(
                            "Could not settle pushes and edits: workspaceId={}, pullRequestId={}",
                            artifact.getWorkspaceId(),
                            artifact.getArtifactId(),
                            e);
                }
            }
        }
        replaceCovered();
    }

    /**
     * One page of pull requests with a queued review and a later one, in key order from where the last page ended,
     * so an uncovered review cannot hold the page and keep the rest waiting. Each pull request commits on its own.
     */
    void replaceCovered() {
        var page = jobs.findQueuedAuthorReviewsWithALaterOne(
                signalValues(), replacementCursorWorkspace, replacementCursorPullRequest, BATCH_SIZE);
        for (var work : page) {
            try {
                transactions.executeWithoutResult(
                        status -> replaceCovered(work.getWorkspaceId(), work.getPullRequestId()));
            } catch (RuntimeException e) {
                log.warn(
                        "Could not replace covered reviews: workspaceId={}, pullRequestId={}",
                        work.getWorkspaceId(),
                        work.getPullRequestId(),
                        e);
            }
        }
        boolean more = page.size() == BATCH_SIZE;
        replacementCursorWorkspace = more ? page.getLast().getWorkspaceId() : 0;
        replacementCursorPullRequest = more ? page.getLast().getPullRequestId() : 0;
    }

    /**
     * Completes each queued automatic push, edit or linked-work review of the author as {@link
     * ReviewRunOutcome#SUPERSEDED} when the newest such review of the pull request covers it: the newest is queued or
     * running, was admitted for the work as it stands (head, title, description and, for linked work, the closing
     * issues' material), and {@link ReplaceableReviewCoverage} holds. Nothing is retargeted. A newest occasion still
     * held in the ledger is not a review yet, so it replaces nothing until its owner admits it. Ready, merge and
     * reviewer reviews are never selected.
     *
     * <p>Locks the pull request first, as {@link #drain} and the mirror's writers do, then the newest review for share
     * so a claim or cancel of it settles first, then the queued ones as a claim locks them, skipping any a claim holds.
     */
    void replaceCovered(long workspaceId, long pullRequestId) {
        pullRequests.lockById(pullRequestId);
        AgentJob newest = jobs.lockLatestAuthorReviewOf(workspaceId, pullRequestId, signalValues())
                .orElse(null);
        // Admitted and not yet finished. A finished review answers through its own record (COALESCED), not here.
        if (newest == null
                || (newest.getStatus() != AgentJobStatus.QUEUED && newest.getStatus() != AgentJobStatus.RUNNING)) {
            return;
        }
        PullRequest pr = pullRequests.findByIdWithAllForGate(pullRequestId).orElse(null);
        if (pr == null || pr.getDeletedAt() != null || !admittedForWorkAsItStands(workspaceId, newest, pr)) return;
        for (AgentJob queued :
                jobs.lockQueuedAuthorReviewsBefore(workspaceId, pullRequestId, signalValues(), newest.getCreatedAt())) {
            if (!coverage.covers(newest, queued)) continue;
            queued.setStatus(AgentJobStatus.COMPLETED);
            queued.setCompletedAt(Instant.now());
            queued.setOutput(mapper.createObjectNode()
                    .put(ReviewRunOutcome.OUTPUT_FIELD, ReviewRunOutcome.SUPERSEDED.name())
                    .put(ReviewRunOutcome.COVERING_JOB_FIELD, newest.getId().toString()));
            AgentJob superseded = jobs.save(queued);
            log.info("Superseded a queued review: jobId={}, coveringJobId={}", superseded.getId(), newest.getId());
            TransactionCallbacks.afterCommit(
                    () -> telemetry.terminal(superseded, AgentJobStatus.COMPLETED, AgentJobTelemetry.age(superseded)));
        }
    }

    /** An equal head alone is not enough: an edit or linked-work review also read the text and issues it names. */
    private boolean admittedForWorkAsItStands(long workspaceId, AgentJob review, PullRequest pr) {
        JsonNode facts = review.getMetadata();
        if (facts == null
                || pr.getHeadRefOid() == null
                || !pr.getHeadRefOid().equals(text(facts, "commit_sha"))
                || !Objects.equals(pr.getTitle(), text(facts, "title"))
                || !Objects.equals(pr.getBody(), text(facts, "body"))) {
            return false;
        }
        if (!ScmSignals.PULL_REQUEST_LINKED_ISSUE_UPDATED.value().equals(text(facts, "signal"))) return true;
        String linked = text(facts, "linked_issue_revision");
        return LinkedWorkItemContentSource.currentClosingMaterialKey(
                        workspaceId, pr, pullRequests.findClosingIssuesById(pr.getId()))
                .map(key -> key.revision().value().equals(linked))
                .orElse(false);
    }

    private static @Nullable String text(JsonNode metadata, String key) {
        JsonNode value = metadata.get(key);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static List<String> signalValues() {
        return SIGNALS.stream().map(SignalName::value).toList();
    }

    void drain(long workspaceId, long pullRequestId, Instant now) {
        // The mirror's writers hold this lock while they write the pull request and queue its occasion in the same
        // transaction, so once it is ours every occasion of the pull request read below is in the group locked next.
        pullRequests.lockById(pullRequestId);
        List<ArtifactSignal> pending = SIGNALS.stream()
                .flatMap(signal -> signals.lockUnsettled(workspaceId, pullRequestId, signal.value()).stream())
                .toList();
        // Held-back occasions have already waited out a quiet period; only queued ones still decide when to settle.
        List<ArtifactSignal> queued = pending.stream()
                .filter(signal -> signal.getState() == SignalState.DEFERRED)
                .toList();
        if (pending.isEmpty()
                || (!queued.isEmpty() && !IssueUpdateCoalescer.isDue(queued, now, QUIET_PERIOD, MAX_WAIT))) {
            return;
        }
        PullRequest pr = pullRequests.findByIdWithAllForGate(pullRequestId).orElse(null);
        if (pr == null || pr.getRepository() == null) {
            pending.forEach(signal -> recorder.markRefused(signal.key(), SignalStateReason.ARTIFACT_GONE));
            return;
        }
        Workspace owner = workspaceResolver
                .resolveAllForRepository(pr.getRepository().getNameWithOwner())
                .stream()
                .filter(workspace -> Objects.equals(workspace.getId(), workspaceId))
                .findFirst()
                .orElse(null);
        if (owner == null) {
            pending.forEach(signal -> recorder.markRefused(signal.key(), SignalStateReason.OUT_OF_REVIEW_SCOPE));
            return;
        }
        boolean mergedRepair = pr.getState() == Issue.State.MERGED;
        if (pr.getState() != Issue.State.OPEN && !mergedRepair) {
            pending.forEach(signal -> recorder.markRefused(signal.key(), SignalStateReason.COALESCED));
            return;
        }
        if (mergedRepair) {
            pending.stream()
                    .filter(signal -> !ScmSignals.PULL_REQUEST_LINKED_ISSUE_UPDATED.equals(
                            signal.key().signalName()))
                    .forEach(signal -> recorder.markRefused(signal.key(), SignalStateReason.COALESCED));
            pending = pending.stream()
                    .filter(signal -> ScmSignals.PULL_REQUEST_LINKED_ISSUE_UPDATED.equals(
                            signal.key().signalName()))
                    .toList();
            if (pending.isEmpty()) return;
        } else {
            pending.stream()
                    .filter(signal -> ScmSignals.PULL_REQUEST_LINKED_ISSUE_UPDATED.equals(
                            signal.key().signalName()))
                    .forEach(signal -> recorder.markRefused(signal.key(), SignalStateReason.COALESCED));
            pending = pending.stream()
                    .filter(signal -> !ScmSignals.PULL_REQUEST_LINKED_ISSUE_UPDATED.equals(
                            signal.key().signalName()))
                    .toList();
            if (pending.isEmpty()) return;
        }
        if (SIGNALS.stream()
                .anyMatch(signal -> IssueUpdateCoalescer.coolingDown(
                        signals, owner, reviewProperties, ScmSignals.PULL_REQUEST, pullRequestId, signal, now))) {
            return;
        }
        final List<ArtifactSignal> settling = pending;
        SignalName occasion = mergedRepair
                ? ScmSignals.PULL_REQUEST_LINKED_ISSUE_UPDATED
                : settling.stream()
                                .anyMatch(signal -> ScmSignals.PULL_REQUEST_SYNCHRONIZED.equals(
                                        signal.key().signalName()))
                        ? ScmSignals.PULL_REQUEST_SYNCHRONIZED
                        : ScmSignals.PULL_REQUEST_EDITED;
        var closing = mergedRepair ? pullRequests.findClosingIssuesById(pullRequestId) : List.<Issue>of();
        if (mergedRepair && closing.stream().anyMatch(issue -> issue.getDeletedAt() != null)) {
            settling.forEach(signal -> recorder.markRefused(signal.key(), SignalStateReason.ARTIFACT_NOT_VISIBLE));
            return;
        }
        List<SignalKey> currentKeys = mergedRepair
                ? LinkedWorkItemContentSource.currentClosingMaterialKey(workspaceId, pr, closing).stream()
                        .toList()
                : SIGNALS.stream()
                        .filter(signal -> !ScmSignals.PULL_REQUEST_LINKED_ISSUE_UPDATED.equals(signal))
                        .flatMap(signal -> ScmSignals.pullRequestKey(
                                workspaceId, pullRequestId, signal, pr.getHeadRefOid(), pr.getTitle(), pr.getBody())
                                .stream())
                        .toList();
        if (currentKeys.stream()
                .anyMatch(key ->
                        settling.stream().noneMatch(signal -> signal.key().equals(key)) && signals.isDeferred(key))) {
            // The work as it stands is queued outside this group; its own deadline decides.
            return;
        }
        Instant deadline = now.minus(MAX_WAIT);
        if (!mergedRepair
                && pr.getHeadRefOid() != null
                && settling.stream()
                        .allMatch(signal -> signal.getStateChangedAt().isAfter(deadline))
                && jobs.existsActivePullRequestReviewOf(
                        workspaceId,
                        pullRequestId,
                        pr.getHeadRefOid(),
                        pr.getTitle(),
                        pr.getBody(),
                        SIGNALS.stream().map(SignalName::value).toList())) {
            // Another occasion's review of the work as it stands may answer this group's practices; the gate can
            // tell only from what it records, so the group waits for it to finish, but no longer than MAX_WAIT from
            // its first member: a review held back for budget may not finish in time.
            return;
        }
        SignalKey current = currentKeys.stream()
                .filter(key -> occasion.equals(key.signalName()))
                .findFirst()
                .orElse(null);
        if (mergedRepair && settling.stream().noneMatch(signal -> signal.key().equals(current))) {
            settling.forEach(signal -> recorder.markRefused(signal.key(), SignalStateReason.COALESCED));
            return;
        }
        // The work is reviewed as it stands whichever row named it: the resubmission reads it afresh.
        ArtifactSignal reviewed = settling.stream()
                .filter(signal -> signal.key().equals(current))
                .findFirst()
                .orElseGet(() -> settling.stream()
                        .filter(signal -> occasion.equals(signal.key().signalName()))
                        .max(Comparator.comparing(ArtifactSignal::getOccurredAt))
                        .orElseThrow());
        for (ArtifactSignal signal : settling) {
            if (signal.key().equals(reviewed.key())) {
                submitter.resubmit(signal);
            } else {
                recorder.markRefused(signal.key(), SignalStateReason.COALESCED);
            }
        }
    }
}
