package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.FeedbackLanePreparationSweeper.SweepOutcome;
import de.tum.cit.aet.hephaestus.agent.handler.conversation.ConversationalDeliveryListener;
import de.tum.cit.aet.hephaestus.agent.handler.inapp.InAppCompositionListener;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.UnpreparedFeedbackLanes;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.data.domain.Pageable;

class FeedbackLanePreparationSweeperTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");
    private static final long WORKSPACE_ID = 7L;

    @Mock
    private AgentJobRepository agentJobRepository;

    @Mock
    private ConversationalDeliveryListener inChatLane;

    @Mock
    private InAppCompositionListener inAppLane;

    private SimpleMeterRegistry meterRegistry;
    private FeedbackLanePreparationSweeper sweeper;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        sweeper = new FeedbackLanePreparationSweeper(agentJobRepository, inChatLane, inAppLane, meterRegistry);
    }

    private static UnpreparedFeedbackLanes bothPending(UUID jobId) {
        return new UnpreparedFeedbackLanes(jobId, WORKSPACE_ID, null, null);
    }

    private void pending(UnpreparedFeedbackLanes... jobs) {
        when(agentJobRepository.findUnpreparedFeedbackLanes(any(), any(), any()))
                .thenReturn(List.of(jobs));
    }

    private double counter(String name, String lane) {
        var counter = meterRegistry.find(name).tag("lane", lane).counter();
        return counter == null ? 0d : counter.count();
    }

    @Test
    @DisplayName("both lanes are run for a job neither lane ever reached")
    void recoversBothLanes() {
        UUID jobId = UUID.randomUUID();
        pending(bothPending(jobId));
        when(inChatLane.prepare(jobId, WORKSPACE_ID)).thenReturn(3);
        when(inAppLane.prepare(jobId, WORKSPACE_ID)).thenReturn(1);

        SweepOutcome outcome = sweeper.sweepNow(NOW);

        assertThat(outcome).isEqualTo(new SweepOutcome(1, 1, 4, 0));
        assertThat(counter("feedback.lane.sweep.recovered", "in-chat")).isEqualTo(1d);
        assertThat(counter("feedback.lane.sweep.recovered", "in-app")).isEqualTo(1d);
    }

    @Test
    @DisplayName("a lane that already ran is left alone — recovery never re-runs what is marked")
    void skipsTheLaneThatAlreadyRan() {
        UUID jobId = UUID.randomUUID();
        pending(new UnpreparedFeedbackLanes(jobId, WORKSPACE_ID, NOW.minus(Duration.ofHours(2)), null));
        when(inAppLane.prepare(jobId, WORKSPACE_ID)).thenReturn(2);

        SweepOutcome outcome = sweeper.sweepNow(NOW);

        verifyNoInteractions(inChatLane);
        assertThat(outcome.preparedUnits()).isEqualTo(2);
        assertThat(counter("feedback.lane.sweep.recovered", "in-chat")).isZero();
    }

    // The bug this replaces was one lost event taking a developer's feedback with it. A sweeper that
    // aborts its pass on the first bad job would reproduce exactly that for everyone behind it.
    @Test
    @DisplayName("one lane's failure does not stop the other lane, or the rest of the pass")
    void isolatesFailures() {
        UUID failing = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        pending(bothPending(failing), bothPending(healthy));
        when(inChatLane.prepare(failing, WORKSPACE_ID)).thenThrow(new IllegalStateException("boom"));
        when(inAppLane.prepare(failing, WORKSPACE_ID)).thenReturn(1);
        when(inChatLane.prepare(healthy, WORKSPACE_ID)).thenReturn(2);
        when(inAppLane.prepare(healthy, WORKSPACE_ID)).thenReturn(1);

        SweepOutcome outcome = sweeper.sweepNow(NOW);

        assertThat(outcome.found()).isEqualTo(2);
        assertThat(outcome.recovered()).isEqualTo(2);
        assertThat(outcome.preparedUnits()).isEqualTo(4);
        assertThat(outcome.stillFailing()).isEqualTo(1);
        assertThat(counter("feedback.lane.sweep.failure", "in-chat")).isEqualTo(1d);
    }

    // A silent failure is the whole defect. If a lane throws on every pass until the job ages out of the
    // window, this counter is the only thing that ever says so.
    @Test
    @DisplayName("a lane that fails is counted, not swallowed")
    void countsAFailingLane() {
        UUID jobId = UUID.randomUUID();
        pending(bothPending(jobId));
        when(inChatLane.prepare(jobId, WORKSPACE_ID)).thenThrow(new IllegalStateException("boom"));
        when(inAppLane.prepare(jobId, WORKSPACE_ID)).thenThrow(new IllegalStateException("boom"));

        SweepOutcome outcome = sweeper.sweepNow(NOW);

        assertThat(outcome).isEqualTo(new SweepOutcome(1, 0, 0, 1));
        assertThat(counter("feedback.lane.sweep.failure", "in-chat")).isEqualTo(1d);
        assertThat(counter("feedback.lane.sweep.failure", "in-app")).isEqualTo(1d);
        assertThat(counter("feedback.lane.sweep.recovered", "in-chat")).isZero();
    }

    // The sweeper marks nothing itself; the lanes do, and only on a path that did not throw. That is what
    // makes a failure retried rather than retired.
    @Test
    @DisplayName("the sweeper never writes a lane mark of its own")
    void neverMarksALaneItself() {
        UUID jobId = UUID.randomUUID();
        pending(bothPending(jobId));
        when(inChatLane.prepare(jobId, WORKSPACE_ID)).thenThrow(new IllegalStateException("boom"));

        sweeper.sweepNow(NOW);

        verify(agentJobRepository, never()).markInChatPrepared(any(), any());
        verify(agentJobRepository, never()).markInAppPrepared(any(), any());
    }

    @Test
    @DisplayName("the window ends before the present, so the listener keeps its own chance first")
    void leavesTheListenerItsSettleWindow() {
        pending();

        sweeper.sweepNow(NOW);

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> until = ArgumentCaptor.forClass(Instant.class);
        verify(agentJobRepository).findUnpreparedFeedbackLanes(from.capture(), until.capture(), any(Pageable.class));

        assertThat(until.getValue()).isEqualTo(NOW.minus(FeedbackLanePreparationSweeper.SETTLE));
        assertThat(until.getValue()).isBefore(NOW);
        assertThat(from.getValue()).isEqualTo(NOW.minus(FeedbackLanePreparationSweeper.LOOKBACK));
        assertThat(from.getValue()).isBefore(until.getValue());
    }

    @Test
    @DisplayName("one pass is bounded, so a backlog is worked off rather than held under one lock")
    void boundsOnePass() {
        pending();

        sweeper.sweepNow(NOW);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(agentJobRepository).findUnpreparedFeedbackLanes(any(), any(), page.capture());

        assertThat(page.getValue().getPageSize()).isEqualTo(FeedbackLanePreparationSweeper.MAX_JOBS_PER_PASS);
        assertThat(page.getValue().getPageNumber()).isZero();
    }

    @Test
    @DisplayName("a healthy instance does no work and touches no lane")
    void doesNothingWhenNothingIsPending() {
        pending();

        assertThat(sweeper.sweepNow(NOW)).isEqualTo(new SweepOutcome(0, 0, 0, 0));
        verify(inChatLane, never()).prepare(any(), any());
        verify(inAppLane, never()).prepare(any(), any());
    }
}
