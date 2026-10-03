package de.tum.cit.aet.hephaestus.integration.core.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;

class PendingSignalReaperTest extends BaseUnitTest {

    private static final SignalLedgerProperties PROPERTIES =
            new SignalLedgerProperties(Duration.ofHours(1), Duration.ofDays(7), 200);

    private final ArtifactSignalRepository repository = mock(ArtifactSignalRepository.class);

    private static ArtifactSignal pendingSignal(String artifactKind, String signalName) {
        ArtifactSignal signal = new ArtifactSignal();
        signal.setId(UUID.randomUUID());
        signal.setArtifactKind(artifactKind);
        signal.setArtifactId(42L);
        signal.setSignalName(signalName);
        signal.setRevision("sha~abc123");
        signal.setOccurredAt(Instant.now());
        signal.setDiscoveredVia(DiscoveredVia.EVENT);
        signal.setState(SignalState.PENDING);
        signal.setStateReason(SignalStateReason.BUDGET_EXHAUSTED);
        signal.setStateChangedAt(Instant.now().minus(Duration.ofHours(2)));
        return signal;
    }

    private PendingSignalReaper reaper(PendingSignalResubmitter... resubmitters) {
        return new PendingSignalReaper(repository, PROPERTIES, List.of(resubmitters));
    }

    private PendingSignalResubmitter resubmitterFor(String kind) {
        PendingSignalResubmitter resubmitter = mock(PendingSignalResubmitter.class);
        when(resubmitter.artifactKind()).thenReturn(ArtifactKind.of(kind));
        return resubmitter;
    }

    @Test
    void shouldReOfferAPendingSignalToTheDomainThatOwnsItsKind() {
        PendingSignalResubmitter pullRequests = resubmitterFor("scm.pull_request");
        PendingSignalResubmitter issues = resubmitterFor("scm.issue");
        ArtifactSignal signal = pendingSignal("scm.pull_request", "scm.pull_request.ready");
        when(repository.findRetryablePending(any(), any(Pageable.class))).thenReturn(List.of(signal));

        reaper(pullRequests, issues).sweep();

        verify(pullRequests).resubmit(signal);
        verify(issues, never()).resubmit(any());
    }

    @Test
    void shouldOnlyOfferSignalsThatHaveWaitedOutTheRetryDelay() {
        // Blockers are lifted by operators, not by events; sweeping faster than that only repeats the
        // same refusal at the cost of another submission attempt.
        Instant before = Instant.now();
        when(repository.findRetryablePending(any(), any(Pageable.class))).thenReturn(List.of());

        reaper().sweep();

        ArgumentCaptor<Instant> retryBefore = ArgumentCaptor.forClass(Instant.class);
        verify(repository).findRetryablePending(retryBefore.capture(), any(Pageable.class));
        assertThat(retryBefore.getValue()).isBefore(before.minus(Duration.ofMinutes(59)));
    }

    @Test
    void shouldRetirePendingSignalsPastTheDeadlineBeforeReOfferingAnything() {
        when(repository.findRetryablePending(any(), any(Pageable.class))).thenReturn(List.of());

        reaper().sweep();

        ArgumentCaptor<Instant> deadline = ArgumentCaptor.forClass(Instant.class);
        verify(repository).lapseStalePending(deadline.capture(), any());
        assertThat(deadline.getValue()).isBefore(Instant.now().minus(Duration.ofDays(6)));
    }

    @Test
    void shouldLeaveASignalAloneWhenNothingInThisDeploymentCanActOnItsKind() {
        ArtifactSignal signal = pendingSignal("docs.document", "docs.document.published");
        when(repository.findRetryablePending(any(), any(Pageable.class))).thenReturn(List.of(signal));

        assertThatCode(() -> reaper().sweep()).doesNotThrowAnyException();
    }

    @Test
    void shouldKeepSweepingAfterOneSignalFails() {
        PendingSignalResubmitter resubmitter = resubmitterFor("scm.pull_request");
        ArtifactSignal failing = pendingSignal("scm.pull_request", "scm.pull_request.ready");
        ArtifactSignal following = pendingSignal("scm.pull_request", "scm.pull_request.merged");
        when(repository.findRetryablePending(any(), any(Pageable.class))).thenReturn(List.of(failing, following));
        doThrow(new IllegalStateException("boom")).when(resubmitter).resubmit(failing);

        reaper(resubmitter).sweep();

        verify(resubmitter).resubmit(following);
    }

    @Test
    void shouldClaimTheWholeBatchBeforeReOfferingAnyOfIt() {
        // Stamping the rows after resubmitting would leave a batch that throws mid-sweep unclaimed and
        // back at the head of the next sweep — the starvation the claim exists to prevent. Without the
        // InOrder assertion this test passes with the claim deleted outright.
        PendingSignalResubmitter resubmitter = resubmitterFor("scm.pull_request");
        ArtifactSignal signal = pendingSignal("scm.pull_request", "scm.pull_request.ready");
        when(repository.findRetryablePending(any(), any(Pageable.class))).thenReturn(List.of(signal));
        doThrow(new IllegalStateException("boom")).when(resubmitter).resubmit(signal);

        reaper(resubmitter).sweep();

        InOrder inOrder = inOrder(repository, resubmitter);
        inOrder.verify(repository).claimPendingForRetry(eq(List.of(signal.getId())), any());
        inOrder.verify(resubmitter).resubmit(signal);
    }

    @Test
    void shouldClaimASignalNoResubmitterCanTake() {
        // The kind with nothing to act on it is left PENDING for the lapse deadline. It must still be
        // claimed, or it sits at the head of every sweep forever, ahead of signals that could be retried.
        ArtifactSignal signal = pendingSignal("docs.document", "docs.document.published");
        when(repository.findRetryablePending(any(), any(Pageable.class))).thenReturn(List.of(signal));

        reaper().sweep();

        verify(repository).claimPendingForRetry(eq(List.of(signal.getId())), any());
    }
}
