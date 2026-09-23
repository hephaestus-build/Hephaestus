package de.tum.cit.aet.hephaestus.agent.job;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignal;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignalRepository;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalState;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceResolver;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class PullRequestPushCoalescerTest extends BaseUnitTest {
    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");
    private static final String OLD_HEAD = "a".repeat(40);
    private static final String NEW_HEAD = "b".repeat(40);
    private static final String SIGNAL = ScmSignals.PULL_REQUEST_SYNCHRONIZED.value();

    private final ArtifactSignalRepository signals = mock(ArtifactSignalRepository.class);
    private final PullRequestRepository pullRequests = mock(PullRequestRepository.class);
    private final SignalRecorder recorder = mock(SignalRecorder.class);
    private final PullRequestSignalResubmitter submitter = mock(PullRequestSignalResubmitter.class);
    private final WorkspaceResolver workspaceResolver = mock(WorkspaceResolver.class);
    private final PullRequestPushCoalescer coalescer = new PullRequestPushCoalescer(
            signals,
            pullRequests,
            recorder,
            submitter,
            workspaceResolver,
            new PracticeReviewProperties(false, 15, 5, false, null),
            mock(TransactionTemplate.class));

    private final PullRequest pullRequest = pullRequest();

    @BeforeEach
    void stubOwner() {
        Workspace workspace = new Workspace();
        workspace.setId(7L);
        when(workspaceResolver.resolveForRepository("owner/repo")).thenReturn(Optional.of(workspace));
        when(pullRequests.findByIdWithAllForGate(42L)).thenReturn(Optional.of(pullRequest));
    }

    @Test
    @DisplayName("a burst of pushes is reviewed once, at the head it settled on")
    void shouldReviewOnlyTheNewestHeadWhenABurstSettles() {
        ArtifactSignal older = signal(OLD_HEAD, 20 * 60);
        ArtifactSignal newest = signal(NEW_HEAD, 11 * 60);
        when(signals.lockDeferred(7L, 42L, SIGNAL)).thenReturn(List.of(older, newest));

        coalescer.drain(7L, 42L, NOW);

        verify(recorder).markRefused(older.key(), SignalStateReason.COALESCED);
        verify(submitter).resubmit(newest);
        verifyNoMoreInteractions(submitter, recorder);
    }

    @Test
    void shouldReviewTheLatestHeadWhenContinuousPushesReachTheMaximumWait() {
        ArtifactSignal older = signal(OLD_HEAD, 60 * 60);
        ArtifactSignal newest = signal(NEW_HEAD, 30);
        when(signals.lockDeferred(7L, 42L, SIGNAL)).thenReturn(List.of(older, newest));

        coalescer.drain(7L, 42L, NOW);

        verify(recorder).markRefused(older.key(), SignalStateReason.COALESCED);
        verify(submitter).resubmit(newest);
        verifyNoMoreInteractions(submitter, recorder);
    }

    @Test
    @DisplayName("a push inside the cooldown waits for it rather than being dropped")
    void shouldWaitOutTheCooldownRatherThanRefuse() {
        ArtifactSignal newest = signal(NEW_HEAD, 11 * 60);
        ArtifactSignal lastReview = signal(OLD_HEAD, 5 * 60);
        lastReview.setState(SignalState.TRIGGERED);
        when(signals.lockDeferred(7L, 42L, SIGNAL)).thenReturn(List.of(newest));
        when(signals.findForArtifact(7L, ScmSignals.PULL_REQUEST.value(), 42L)).thenReturn(List.of(lastReview));

        coalescer.drain(7L, 42L, NOW);

        verifyNoInteractions(recorder, submitter);
    }

    @Test
    @DisplayName("a push the merge overtook is left to the merge occasion")
    void shouldLeaveAMergedPullRequestToItsMergeOccasion() {
        pullRequest.setState(Issue.State.MERGED);
        ArtifactSignal newest = signal(NEW_HEAD, 11 * 60);
        when(signals.lockDeferred(7L, 42L, SIGNAL)).thenReturn(List.of(newest));

        coalescer.drain(7L, 42L, NOW);

        verify(recorder).markRefused(newest.key(), SignalStateReason.COALESCED);
        verifyNoInteractions(submitter);
    }

    @Test
    @DisplayName("a push still inside the quiet period is not reviewed yet")
    void shouldHoldAPushInsideTheQuietPeriod() {
        when(signals.lockDeferred(7L, 42L, SIGNAL)).thenReturn(List.of(signal(NEW_HEAD, 60)));

        coalescer.drain(7L, 42L, NOW);

        verifyNoInteractions(recorder, submitter);
    }

    private static ArtifactSignal signal(String head, int ageSeconds) {
        Workspace workspace = new Workspace();
        workspace.setId(7L);
        ArtifactSignal signal = new ArtifactSignal();
        signal.setWorkspace(workspace);
        signal.setArtifactId(42L);
        signal.setSignalName(SIGNAL);
        signal.setRevision(
                ScmSignals.pullRequestKey(7L, 42L, ScmSignals.PULL_REQUEST_SYNCHRONIZED, head, "Add feature", null)
                        .orElseThrow()
                        .revision()
                        .value());
        signal.setOccurredAt(NOW.minusSeconds(ageSeconds));
        signal.setStateChangedAt(NOW.minusSeconds(ageSeconds));
        return signal;
    }

    private static PullRequest pullRequest() {
        Repository repository = new Repository();
        repository.setId(1L);
        repository.setNameWithOwner("owner/repo");
        PullRequest pr = new PullRequest();
        pr.setId(42L);
        pr.setRepository(repository);
        pr.setState(Issue.State.OPEN);
        pr.setTitle("Add feature");
        pr.setHeadRefOid(NEW_HEAD);
        return pr;
    }
}
