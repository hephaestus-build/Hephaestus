package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionContinuation;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobClaimService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobTerminal;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A composition run composes or withholds as its claim decided, holds its inputs across its requests, and never
 * composes on a decision that its replica lost.
 */
@Tag("unit")
class ReviewComposeSandboxKindTest {
    private final ReviewJobClaimService claims = mock(ReviewJobClaimService.class);
    private final ReviewJobRepository jobs = mock(ReviewJobRepository.class);
    private final ResultAdmissionService admission = mock(ResultAdmissionService.class);
    private final AgentRuns runs = mock(AgentRuns.class);
    private final ReviewAttemptCustody custody = new ReviewAttemptCustody(runs);
    private final ReviewCompositionContinuation continuation = mock(ReviewCompositionContinuation.class);
    private final CompositionSessions sessions = mock(CompositionSessions.class);
    private final ReviewJobTerminal terminal = mock(ReviewJobTerminal.class);
    private final ReviewRunKind reviewRuns = mock(ReviewRunKind.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ReviewComposeSandboxKind kind = new ReviewComposeSandboxKind(
            claims,
            jobs,
            admission,
            custody,
            continuation,
            sessions,
            terminal,
            reviewRuns,
            runs,
            new TransactionTemplate(mock(PlatformTransactionManager.class)),
            meters,
            Tracer.NOOP);

    private final AgentRunFence fence = new AgentRunFence(UUID.randomUUID(), 7L, 2);
    private final SandboxRun run = new SandboxRun(
            new AgentSandboxes.LiveSandbox(UUID.randomUUID(), 7L, "review:1", AgentSandboxKind.REVIEW),
            fence,
            AgentRunKind.REVIEW_COMPOSE,
            1);
    private final ResultAdmissionService.Composing composing = new ResultAdmissionService.Composing(
            RuntimeReviewTestData.job(),
            new ResultAdmissionService.AdmissionIdentity(UUID.randomUUID(), 7L, 1, fence.runId()),
            null);
    private final AtomicInteger closed = new AtomicInteger();

    @Test
    void shouldWithholdOnceAndMakeNoModelCallWhenTheClaimOnlyWithholds() {
        claim(CompositionStop.BUDGET);
        when(continuation.stageWithoutInputs(composing.job())).thenReturn(restored());

        kind.context(run);
        kind.prepare(run);

        verify(sessions, times(1)).withholdOpen(composing.job(), composing.identity(), CompositionStop.BUDGET);
        verify(continuation, never()).restore(any());
        assertThat(kind.attempt(run)).isEmpty();
    }

    @Test
    void shouldHoldTheRestoredInputsAcrossTheRunsRequests() {
        claim(null);
        when(continuation.restore(composing.job())).thenReturn(restored());

        kind.prepare(run);
        kind.prepare(run);

        verify(continuation, times(1)).restore(composing.job());
        verify(sessions, never()).withholdOpen(any(), any(), any());
    }

    @Test
    void shouldWithholdWithTheLossWhenTheInputsWereErased() {
        claim(null);
        when(continuation.restore(composing.job()))
                .thenThrow(new ReviewCompositionContinuation.InputsUnavailableException("erased", true));
        when(continuation.stageWithoutInputs(composing.job())).thenReturn(restored());

        kind.prepare(run);

        verify(sessions).withholdOpen(composing.job(), composing.identity(), CompositionStop.INPUTS_ERASED);
    }

    @Test
    void shouldQueueTheRunAgainForFreeWhenThisReplicaLostItsClaimsDecision() {
        when(admission.composing(fence)).thenReturn(composing);

        assertThatThrownBy(() -> kind.prepare(run)).isInstanceOf(StaleRunException.class);

        verify(reviewRuns).requeue(composing.identity().jobId(), fence, AgentRuns.Charge.FREE, Duration.ZERO, null);
        verify(continuation, never()).restore(any());
        verify(sessions, never()).withholdOpen(any(), any(), any());
    }

    @Test
    void shouldCompleteTheJobAndReleaseItsInputsWhenTheRunCompletes() {
        claim(null);
        when(continuation.restore(composing.job())).thenReturn(restored());
        when(terminal.completeComposed(composing.identity().jobId(), fence)).thenReturn(true);
        kind.prepare(run);

        assertThat(kind.end(run, SandboxRunKind.Outcome.COMPLETED)).isTrue();

        assertThat(closed).hasValue(1);
        assertThat(meters.get(AgentMetrics.AGENT_JOB_STAGE_DURATION)
                        .tag("stage", "composition")
                        .tag("outcome", "ok")
                        .timer()
                        .count())
                .isOne();
    }

    @Test
    void shouldSpendAnAttemptWhenTheRunIsHandedOn() {
        claim(null);
        when(reviewRuns.requeue(composing.identity().jobId(), fence, AgentRuns.Charge.SPEND, Duration.ZERO, null))
                .thenReturn(true);

        assertThat(kind.end(run, SandboxRunKind.Outcome.HANDED_ON)).isTrue();

        verify(terminal, never()).completeComposed(any(), any());
    }

    @Test
    void shouldLeaseNothingWhenTheClaimEndedOrRequeuedTheRun() {
        when(claims.claimLeased(fence)).thenReturn(Optional.empty());

        assertThat(kind.claimed(run)).isFalse();
    }

    private void claim(@Nullable CompositionStop withholdOnly) {
        var started = mock(ReviewJobClaimService.ClaimResult.class);
        when(started.withholdOnly()).thenReturn(withholdOnly);
        when(claims.claimLeased(fence)).thenReturn(Optional.of(started));
        when(admission.composing(fence)).thenReturn(composing);
        when(jobs.findIdByRun(eq(fence.runId()), eq(fence.workspaceId())))
                .thenReturn(Optional.of(composing.identity().jobId()));
        assertThat(kind.claimed(run)).isTrue();
    }

    private ReviewCompositionContinuation.Restored restored() {
        return new ReviewCompositionContinuation.Restored(
                new PreparedJobInputs(Map.of(), List.of(), List.of(closed::incrementAndGet), null, null), Map.of());
    }
}
