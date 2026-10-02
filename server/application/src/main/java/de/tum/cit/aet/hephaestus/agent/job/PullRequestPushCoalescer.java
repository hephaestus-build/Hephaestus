package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.context.providers.LinkedWorkItemContentSource;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Coalesces pushes and title or description edits into one review of the work as it stands after
 * {@link #QUIET_PERIOD}, or {@link #MAX_WAIT} from the first of them. The workspace cooldown defers the
 * group instead of refusing it. A push in the group makes it a push review; edits alone are an edit review.
 *
 * <p>Also the pending-signal reaper's way back in for a pull request: a push or edit admission held back is
 * re-offered through the same settlement, so it waits for newer edits and pushes as the sweep does and
 * cannot review the work ahead of them. Every other pull-request occasion goes straight to the submitter.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(prefix = "hephaestus.agent", name = "enabled", havingValue = "true")
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
            if (signal == reviewed) {
                submitter.resubmit(signal);
            } else {
                recorder.markRefused(signal.key(), SignalStateReason.COALESCED);
            }
        }
    }
}
