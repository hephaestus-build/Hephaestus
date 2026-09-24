package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignal;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignalRepository;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceResolver;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class IssueUpdateCoalescerTest extends BaseUnitTest {
    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");
    private final ArtifactSignalRepository signals = mock(ArtifactSignalRepository.class);
    private final IssueRepository issues = mock(IssueRepository.class);
    private final SignalRecorder recorder = mock(SignalRecorder.class);
    private final IssueSignalResubmitter submitter = mock(IssueSignalResubmitter.class);
    private final WorkspaceResolver workspaceResolver = mock(WorkspaceResolver.class);
    private final IssueUpdateCoalescer coalescer = new IssueUpdateCoalescer(
            signals, issues, recorder, submitter, workspaceResolver, mock(TransactionTemplate.class));

    @Test
    void shouldSubmitOnlyTheCurrentSnapshotWhenABurstSettles() {
        Issue issue = issue();
        stubOwningWorkspace();
        ArtifactSignal old = signal("old", 60);
        ArtifactSignal current = signal(
                ScmSignals.issueKey(7L, ScmSignals.ISSUE_UPDATED, ScmEventPayload.IssueData.from(issue))
                        .orElseThrow()
                        .revision()
                        .value(),
                30);
        when(signals.lockDeferred(7L, 42L, ScmSignals.ISSUE_UPDATED.value())).thenReturn(List.of(old, current));
        when(issues.findByIdWithRepositoryAndAssignees(42L)).thenReturn(Optional.of(issue));

        coalescer.drain(7L, 42L, NOW);

        verify(recorder).markRefused(old.key(), SignalStateReason.COALESCED);
        verify(submitter).resubmit(current);
        verifyNoMoreInteractions(submitter, recorder);
    }

    @Test
    void shouldNotReviveAnIntermediateSnapshotWhenCurrentContentWasAlreadySettled() {
        stubOwningWorkspace();
        ArtifactSignal old = signal("old", 60);
        when(signals.lockDeferred(7L, 42L, ScmSignals.ISSUE_UPDATED.value())).thenReturn(List.of(old));
        when(issues.findByIdWithRepositoryAndAssignees(42L)).thenReturn(Optional.of(issue()));

        coalescer.drain(7L, 42L, NOW);

        verify(recorder).markRefused(old.key(), SignalStateReason.COALESCED);
        verifyNoInteractions(submitter);
    }

    @Test
    void shouldPreserveTheBurstDeadlineWhenTheCurrentSnapshotArrivedAfterLocking() {
        Issue issue = issue();
        stubOwningWorkspace();
        var current = ScmSignals.issueKey(7L, ScmSignals.ISSUE_UPDATED, ScmEventPayload.IssueData.from(issue))
                .orElseThrow();
        when(signals.lockDeferred(7L, 42L, ScmSignals.ISSUE_UPDATED.value())).thenReturn(List.of(signal("old", 300)));
        when(issues.findByIdWithRepositoryAndAssignees(42L)).thenReturn(Optional.of(issue));
        when(signals.isDeferred(current)).thenReturn(true);

        coalescer.drain(7L, 42L, NOW);

        verifyNoInteractions(recorder, submitter);
    }

    @Test
    void shouldRefuseTheWholeGroupWhenTheRepositoryWasRekeyedToAnotherWorkspace() {
        Issue issue = issue();
        Workspace other = new Workspace();
        other.setId(9L);
        when(workspaceResolver.resolveAllForRepository("owner/repo")).thenReturn(List.of(other));
        ArtifactSignal old = signal("old", 60);
        when(signals.lockDeferred(7L, 42L, ScmSignals.ISSUE_UPDATED.value())).thenReturn(List.of(old));
        when(issues.findByIdWithRepositoryAndAssignees(42L)).thenReturn(Optional.of(issue));

        coalescer.drain(7L, 42L, NOW);

        verify(recorder).markRefused(old.key(), SignalStateReason.OUT_OF_REVIEW_SCOPE);
        verifyNoInteractions(submitter);
    }

    @Test
    void shouldRefuseTheWholeGroupWhenNoWorkspaceMonitorsTheRepositoryAnymore() {
        Issue issue = issue();
        when(workspaceResolver.resolveAllForRepository("owner/repo")).thenReturn(List.of());
        ArtifactSignal old = signal("old", 60);
        when(signals.lockDeferred(7L, 42L, ScmSignals.ISSUE_UPDATED.value())).thenReturn(List.of(old));
        when(issues.findByIdWithRepositoryAndAssignees(42L)).thenReturn(Optional.of(issue));

        coalescer.drain(7L, 42L, NOW);

        verify(recorder).markRefused(old.key(), SignalStateReason.OUT_OF_REVIEW_SCOPE);
        verifyNoInteractions(submitter);
    }

    @Test
    void shouldRecheckQuietPeriodAfterLockingWhenAnotherDeliveryArrives() {
        when(signals.lockDeferred(7L, 42L, ScmSignals.ISSUE_UPDATED.value()))
                .thenReturn(List.of(signal("old", 60), signal("new", 1)));
        coalescer.drain(7L, 42L, NOW);
        verifyNoInteractions(issues, recorder, submitter);
    }

    @Test
    void shouldBoundDelayWhenEditsNeverBecomeQuiet() {
        assertThat(IssueUpdateCoalescer.isDue(List.of(signal("old", 300), signal("new", 0)), NOW))
                .isTrue();
        assertThat(IssueUpdateCoalescer.isDue(List.of(signal("old", 299), signal("new", 0)), NOW))
                .isFalse();
        assertThat(IssueUpdateCoalescer.isDue(List.of(signal("only", 30)), NOW)).isTrue();
    }

    @Test
    void shouldDoNothingWhenAnotherConsumerAlreadySettledTheGroup() {
        when(signals.lockDeferred(7L, 42L, ScmSignals.ISSUE_UPDATED.value())).thenReturn(List.of());
        coalescer.drain(7L, 42L, NOW);
        verifyNoInteractions(issues, recorder, submitter);
    }

    @Test
    void shouldLeaveTheRetrospectiveToTheCloseOccasion() {
        Issue issue = issue();
        issue.setState(Issue.State.CLOSED);
        stubOwningWorkspace();
        ArtifactSignal old = signal("old", 60);
        when(signals.lockDeferred(7L, 42L, ScmSignals.ISSUE_UPDATED.value())).thenReturn(List.of(old));
        when(issues.findByIdWithRepositoryAndAssignees(42L)).thenReturn(Optional.of(issue));
        coalescer.drain(7L, 42L, NOW);
        verify(recorder).markRefused(old.key(), SignalStateReason.COALESCED);
        verifyNoInteractions(submitter);
    }

    @Test
    void shouldHoldTombstonedWorkRatherThanReviewIt() {
        Issue issue = issue();
        issue.setDeletedAt(NOW);
        stubOwningWorkspace();
        ArtifactSignal old = signal("old", 60);
        when(signals.lockDeferred(7L, 42L, ScmSignals.ISSUE_UPDATED.value())).thenReturn(List.of(old));
        when(issues.findByIdWithRepositoryAndAssignees(42L)).thenReturn(Optional.of(issue));
        coalescer.drain(7L, 42L, NOW);
        verify(recorder).markRefused(old.key(), SignalStateReason.ARTIFACT_NOT_VISIBLE);
        verifyNoInteractions(submitter);
    }

    @Test
    void shouldLapseDeletedArtifacts() {
        ArtifactSignal old = signal("old", 60);
        when(signals.lockDeferred(7L, 42L, ScmSignals.ISSUE_UPDATED.value())).thenReturn(List.of(old));
        when(issues.findByIdWithRepositoryAndAssignees(42L)).thenReturn(Optional.empty());
        coalescer.drain(7L, 42L, NOW);
        verify(recorder).markRefused(old.key(), SignalStateReason.ARTIFACT_GONE);
        verifyNoInteractions(submitter);
    }

    private void stubOwningWorkspace() {
        Workspace workspace = new Workspace();
        workspace.setId(7L);
        when(workspaceResolver.resolveAllForRepository("owner/repo")).thenReturn(List.of(workspace));
    }

    private static ArtifactSignal signal(String revision, int ageSeconds) {
        Workspace workspace = new Workspace();
        workspace.setId(7L);
        ArtifactSignal signal = new ArtifactSignal();
        signal.setWorkspace(workspace);
        signal.setArtifactId(42L);
        signal.setSignalName(ScmSignals.ISSUE_UPDATED.value());
        signal.setRevision(revision);
        signal.setStateChangedAt(NOW.minusSeconds(ageSeconds));
        return signal;
    }

    private static Issue issue() {
        Repository repository = new Repository();
        repository.setId(1L);
        repository.setNameWithOwner("owner/repo");
        Issue issue = new Issue();
        issue.setId(42L);
        issue.setNumber(1);
        issue.setTitle("Current title");
        issue.setState(Issue.State.OPEN);
        issue.setRepository(repository);
        return issue;
    }
}
