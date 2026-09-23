package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignal;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignalRepository;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Coalesces pushes at the newest head after {@link #QUIET_PERIOD}, or {@link #MAX_WAIT}
 * from the first push. The workspace cooldown defers the group instead of refusing it.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(prefix = "hephaestus.agent", name = "enabled", havingValue = "true")
@WorkspaceAgnostic("Discovers due push occasions across workspaces; each drain is workspace-scoped")
public class PullRequestPushCoalescer {

    static final Duration QUIET_PERIOD = Duration.ofMinutes(10);
    static final Duration MAX_WAIT = Duration.ofMinutes(60);
    private static final int BATCH_SIZE = 100;
    private static final String SIGNAL = ScmSignals.PULL_REQUEST_SYNCHRONIZED.value();
    private static final Logger log = LoggerFactory.getLogger(PullRequestPushCoalescer.class);

    private final ArtifactSignalRepository signals;
    private final PullRequestRepository pullRequests;
    private final SignalRecorder recorder;
    private final PullRequestSignalResubmitter submitter;
    private final WorkspaceResolver workspaceResolver;
    private final PracticeReviewProperties reviewProperties;
    private final TransactionTemplate transactions;

    public PullRequestPushCoalescer(
            ArtifactSignalRepository signals,
            PullRequestRepository pullRequests,
            SignalRecorder recorder,
            PullRequestSignalResubmitter submitter,
            WorkspaceResolver workspaceResolver,
            PracticeReviewProperties reviewProperties,
            TransactionTemplate transactions) {
        this.signals = signals;
        this.pullRequests = pullRequests;
        this.recorder = recorder;
        this.submitter = submitter;
        this.workspaceResolver = workspaceResolver;
        this.reviewProperties = reviewProperties;
        this.transactions = transactions;
    }

    /** Locked for the same reason as {@link IssueUpdateCoalescer#sweep}. */
    @Scheduled(fixedDelay = 30, initialDelay = 30, timeUnit = TimeUnit.SECONDS)
    @SchedulerLock(name = "pull-request-push-coalescer", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
    public void sweep() {
        Instant queryNow = Instant.now();
        for (var artifact :
                signals.findDueDeferred(SIGNAL, queryNow.minus(QUIET_PERIOD), queryNow.minus(MAX_WAIT), BATCH_SIZE)) {
            try {
                Instant now = Instant.now();
                signals.noteDeferredAttempt(artifact.getWorkspaceId(), artifact.getArtifactId(), SIGNAL, now);
                transactions.executeWithoutResult(
                        status -> drain(artifact.getWorkspaceId(), artifact.getArtifactId(), now));
            } catch (RuntimeException e) {
                log.warn(
                        "Could not settle pushes: workspaceId={}, pullRequestId={}",
                        artifact.getWorkspaceId(),
                        artifact.getArtifactId(),
                        e);
            }
        }
    }

    void drain(long workspaceId, long pullRequestId, Instant now) {
        List<ArtifactSignal> pending = signals.lockDeferred(workspaceId, pullRequestId, SIGNAL);
        if (pending.isEmpty() || !IssueUpdateCoalescer.isDue(pending, now, QUIET_PERIOD, MAX_WAIT)) {
            return;
        }
        PullRequest pr = pullRequests.findByIdWithAllForGate(pullRequestId).orElse(null);
        if (pr == null || pr.getRepository() == null) {
            pending.forEach(signal -> recorder.markRefused(signal.key(), SignalStateReason.ARTIFACT_GONE));
            return;
        }
        Workspace owner = workspaceResolver
                .resolveForRepository(pr.getRepository().getNameWithOwner())
                .orElse(null);
        if (owner == null || !Objects.equals(owner.getId(), workspaceId)) {
            pending.forEach(signal -> recorder.markRefused(signal.key(), SignalStateReason.OUT_OF_REVIEW_SCOPE));
            return;
        }
        if (pr.getState() != Issue.State.OPEN) {
            // Merging and closing are occasions of their own.
            pending.forEach(signal -> recorder.markRefused(signal.key(), SignalStateReason.COALESCED));
            return;
        }
        int cooldown = owner.getReviewSettings().resolveCooldownMinutes(reviewProperties.cooldownMinutes());
        Instant lastReview = lastPushReview(workspaceId, pullRequestId);
        if (cooldown > 0
                && lastReview != null
                && lastReview.plus(Duration.ofMinutes(cooldown)).isAfter(now)) {
            return;
        }
        SignalKey current = ScmSignals.pullRequestKey(
                        workspaceId,
                        pullRequestId,
                        ScmSignals.PULL_REQUEST_SYNCHRONIZED,
                        pr.getHeadRefOid(),
                        pr.getTitle(),
                        pr.getBody())
                .orElse(null);
        if (current != null
                && pending.stream().noneMatch(signal -> signal.key().equals(current))
                && signals.isDeferred(current)) {
            // The newest push committed after the group was locked; its own deadline decides.
            return;
        }
        // The newest head is reviewed whichever push named it: the resubmission reads the head as it is.
        ArtifactSignal reviewed = pending.stream()
                .filter(signal -> signal.key().equals(current))
                .findFirst()
                .orElseGet(() -> pending.stream()
                        .max(Comparator.comparing(ArtifactSignal::getOccurredAt))
                        .orElseThrow());
        for (ArtifactSignal signal : pending) {
            if (signal == reviewed) {
                submitter.resubmit(signal);
            } else {
                recorder.markRefused(signal.key(), SignalStateReason.COALESCED);
            }
        }
    }

    private @Nullable Instant lastPushReview(long workspaceId, long pullRequestId) {
        return signals.findForArtifact(workspaceId, ScmSignals.PULL_REQUEST.value(), pullRequestId).stream()
                .filter(signal -> SIGNAL.equals(signal.getSignalName()) && signal.getState() == SignalState.TRIGGERED)
                .map(ArtifactSignal::getStateChangedAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }
}
