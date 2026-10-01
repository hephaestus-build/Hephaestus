package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignal;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignalRepository;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalState;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnServerRole
@ConditionalOnProperty(prefix = "hephaestus.agent", name = "enabled", havingValue = "true")
@WorkspaceAgnostic("Discovers due issue occasions across workspaces; each drain is workspace-scoped")
public class IssueUpdateCoalescer {

    static final Duration QUIET_PERIOD = Duration.ofSeconds(30);
    static final Duration MAX_WAIT = Duration.ofMinutes(5);
    private static final int BATCH_SIZE = 100;
    private static final Logger log = LoggerFactory.getLogger(IssueUpdateCoalescer.class);

    private final ArtifactSignalRepository signals;
    private final IssueRepository issues;
    private final SignalRecorder recorder;
    private final IssueSignalResubmitter submitter;
    private final WorkspaceResolver workspaceResolver;
    private final PracticeReviewProperties reviewProperties;
    private final TransactionTemplate transactions;

    public IssueUpdateCoalescer(
            ArtifactSignalRepository signals,
            IssueRepository issues,
            SignalRecorder recorder,
            IssueSignalResubmitter submitter,
            WorkspaceResolver workspaceResolver,
            PracticeReviewProperties reviewProperties,
            TransactionTemplate transactions) {
        this.signals = signals;
        this.issues = issues;
        this.recorder = recorder;
        this.submitter = submitter;
        this.workspaceResolver = workspaceResolver;
        this.reviewProperties = reviewProperties;
        this.transactions = transactions;
    }

    /**
     * Held under a lock rather than made idempotent, for the same reason as {@link
     * de.tum.cit.aet.hephaestus.integration.core.signal.PendingSignalReaper#sweep}: a resubmission two
     * replicas both attempted would be settled by the job idempotency key, but only after both had paid
     * for the work of getting there.
     */
    @Scheduled(fixedDelay = 5, initialDelay = 5, timeUnit = TimeUnit.SECONDS)
    @SchedulerLock(name = "issue-update-coalescer", lockAtMostFor = "PT1M", lockAtLeastFor = "PT5S")
    public void sweep() {
        Instant queryNow = Instant.now();
        for (var artifact : signals.findDueDeferred(
                ScmSignals.ISSUE_UPDATED.value(), queryNow.minus(QUIET_PERIOD), queryNow.minus(MAX_WAIT), BATCH_SIZE)) {
            try {
                // Read fresh per artifact: a blocking FOR UPDATE in drain() can hold this batch long
                // enough that queryNow biases the quiet-period recheck toward not-yet-due.
                Instant now = Instant.now();
                signals.noteDeferredAttempt(
                        artifact.getWorkspaceId(), artifact.getArtifactId(), ScmSignals.ISSUE_UPDATED.value(), now);
                transactions.executeWithoutResult(
                        status -> drain(artifact.getWorkspaceId(), artifact.getArtifactId(), now));
            } catch (RuntimeException e) {
                log.warn(
                        "Could not settle issue updates: workspaceId={}, issueId={}",
                        artifact.getWorkspaceId(),
                        artifact.getArtifactId(),
                        e);
            }
        }
    }

    void drain(long workspaceId, long issueId, Instant now) {
        List<ArtifactSignal> pending = signals.lockDeferred(workspaceId, issueId, ScmSignals.ISSUE_UPDATED.value());
        if (pending.isEmpty() || !isDue(pending, now)) {
            return;
        }
        Issue issue = issues.findByIdWithRepositoryAndAssignees(issueId).orElse(null);
        if (issue == null || issue.getRepository() == null || issue.isPullRequest()) {
            pending.forEach(signal -> recorder.markRefused(signal.key(), SignalStateReason.ARTIFACT_GONE));
            return;
        }
        // Recheck this workspace's monitor after the quiet period: a repository can move or be
        // shared by several workspaces (ADR 0024 § re-keying).
        Workspace owner = workspaceResolver
                .resolveAllForRepository(issue.getRepository().getNameWithOwner())
                .stream()
                .filter(workspace -> Objects.equals(workspace.getId(), workspaceId))
                .findFirst()
                .orElse(null);
        if (owner == null) {
            pending.forEach(signal -> recorder.markRefused(signal.key(), SignalStateReason.OUT_OF_REVIEW_SCOPE));
            return;
        }
        if (issue.getDeletedAt() != null) {
            pending.forEach(signal -> recorder.markRefused(signal.key(), SignalStateReason.ARTIFACT_NOT_VISIBLE));
            return;
        }
        if (issue.getState() == Issue.State.CLOSED) {
            // Closing has its own review occasion.
            pending.forEach(signal -> recorder.markRefused(signal.key(), SignalStateReason.COALESCED));
            return;
        }
        if (coolingDown(signals, owner, reviewProperties, ScmSignals.ISSUE, issueId, ScmSignals.ISSUE_UPDATED, now)) {
            return;
        }
        SignalKey current = ScmSignals.issueKey(
                        workspaceId, ScmSignals.ISSUE_UPDATED, ScmEventPayload.IssueData.from(issue))
                .orElseThrow();
        if (pending.stream().noneMatch(signal -> signal.key().equals(current)) && signals.isDeferred(current)) {
            // Preserve the deadline if the current snapshot committed after the group was locked.
            return;
        }
        for (ArtifactSignal signal : pending) {
            if (signal.key().equals(current)) {
                submitter.resubmit(signal);
            } else {
                recorder.markRefused(signal.key(), SignalStateReason.COALESCED);
            }
        }
    }

    static boolean isDue(List<ArtifactSignal> pending, Instant now) {
        return isDue(pending, now, QUIET_PERIOD, MAX_WAIT);
    }

    /** Due once the whole group has been quiet for {@code quiet}, or its oldest member has waited {@code maxWait}. */
    static boolean isDue(List<ArtifactSignal> pending, Instant now, Duration quiet, Duration maxWait) {
        Instant quietBefore = now.minus(quiet);
        Instant deadline = now.minus(maxWait);
        return pending.stream().allMatch(signal -> !signal.getStateChangedAt().isAfter(quietBefore))
                || pending.stream()
                        .anyMatch(signal -> !signal.getStateChangedAt().isAfter(deadline));
    }

    /**
     * Whether the workspace's cooldown still covers the last review this signal occasioned on the
     * artifact. A group due inside it stays deferred rather than being submitted: submission would
     * refuse it for good ({@link SignalStateReason#COOLDOWN_ACTIVE}), losing the very change that was
     * made in answer to that review.
     */
    static boolean coolingDown(
            ArtifactSignalRepository signals,
            Workspace owner,
            PracticeReviewProperties reviewProperties,
            ArtifactKind kind,
            long artifactId,
            SignalName signal,
            Instant now) {
        int cooldown = owner.getReviewSettings().resolveCooldownMinutes(reviewProperties.cooldownMinutes());
        if (cooldown <= 0) {
            return false;
        }
        Instant windowStart = now.minus(Duration.ofMinutes(cooldown));
        return signals.findForArtifact(owner.getId(), kind.value(), artifactId).stream()
                .anyMatch(row -> signal.value().equals(row.getSignalName())
                        && row.getState() == SignalState.TRIGGERED
                        && row.getStateChangedAt().isAfter(windowStart));
    }
}
