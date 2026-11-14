package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionClaim;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionContinuation;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobClaimService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobTerminal;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.metrics.ReviewStageTelemetry;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.core.lock.ClusterLock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** A composition reads its persisted claim decision on every replica and holds only a local input cache. */
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
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final ClusterLock locks = mock(ClusterLock.class);
    private final ReviewCompositionClaim gateway =
            new ReviewCompositionClaim(jobs, new TransactionTemplate(transactionManager), mapper, locks);
    private final ReviewComposeSandboxKind kind = replica(custody);

    private ReviewComposeSandboxKind replica(ReviewAttemptCustody localCustody) {
        return new ReviewComposeSandboxKind(
                claims,
                jobs,
                admission,
                localCustody,
                continuation,
                sessions,
                terminal,
                reviewRuns,
                runs,
                gateway,
                meters,
                Tracer.NOOP);
    }

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

    @BeforeEach
    void storeGatewayDescriptors() {
        composing.job().setId(composing.identity().jobId());
        composing.job().setWorkspaceId(fence.workspaceId());
        composing.job().setRunId(fence.runId());
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(locks.call(anyString(), any(), any()))
                .thenAnswer(call ->
                        Optional.of(call.<Supplier<Object>>getArgument(2).get()));
        when(jobs.saveCompositionClaim(any(), anyLong(), any(), anyInt(), anyInt(), anyString()))
                .thenAnswer(call -> {
                    var capture = mapper.createObjectNode();
                    capture.set("compositionClaim", mapper.readTree(call.<String>getArgument(5)));
                    composing.job().setCapture(capture);
                    return 1;
                });
    }

    @Test
    void shouldRouteModelCallsWithTheRunsLeaseEpoch() {
        claim(null);

        assertThat(kind.attempt(run)).hasValueSatisfying(attempt -> {
            assertThat(attempt.runId()).isEqualTo(fence.runId());
            assertThat(attempt.epoch()).isEqualTo(fence.epoch());
        });
    }

    @Test
    void shouldWithholdAndMakeNoModelCallWhenTheClaimOnlyWithholds() {
        claim(CompositionStop.BUDGET);
        when(continuation.stageWithoutInputs(composing.job())).thenReturn(restored());

        kind.context(run);
        kind.prepare(run);

        var other = replica(new ReviewAttemptCustody(runs));
        other.prepare(run);

        verify(sessions).withholdOpen(composing.job(), composing.identity(), CompositionStop.BUDGET);
        verify(continuation, never()).restore(any());
        assertThat(kind.attempt(run)).isEmpty();
    }

    @Test
    void shouldRetryBusyCompositionWithoutWithholdingOrChangingTheRun() {
        claim(CompositionStop.BUDGET);
        when(continuation.stageWithoutInputs(composing.job())).thenReturn(restored());
        doReturn(Optional.empty()).when(locks).call(anyString(), any(), any());

        assertThatThrownBy(() -> kind.prepare(run)).isInstanceOfSatisfying(ErrorResponseException.class, failure -> {
            assertThat(failure.getStatusCode().value()).isEqualTo(503);
            assertThat(failure.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        });

        verify(sessions, never()).withholdOpen(any(), any(), any());
        verify(reviewRuns, never()).requeue(any(), any(), any(), any(), any());
    }

    @Test
    void shouldNotTakeTheWithholdingLockAgainOnceTheClaimIsWithheld() {
        claim(CompositionStop.BUDGET);
        when(continuation.stageWithoutInputs(composing.job())).thenReturn(restored());
        var other = replica(new ReviewAttemptCustody(runs));
        kind.prepare(run);
        clearInvocations(locks, sessions);

        other.prepare(run);

        verify(locks, never()).call(anyString(), any(), any());
        verify(sessions, never()).withholdOpen(any(), any(), any());
    }

    @Test
    void shouldNotTakeTheWithholdingLockAgainWhenTheInputsLossWasAlreadyWithheld() {
        claim(null);
        when(continuation.restore(composing.job()))
                .thenThrow(new ReviewCompositionContinuation.InputsUnavailableException("gone", false));
        when(continuation.stageWithoutInputs(composing.job())).thenReturn(restored());
        var other = replica(new ReviewAttemptCustody(runs));
        kind.prepare(run);
        verify(sessions).withholdOpen(composing.job(), composing.identity(), CompositionStop.INPUTS_GONE);
        clearInvocations(locks, sessions);

        other.prepare(run);

        verify(locks, never()).call(anyString(), any(), any());
        verify(sessions, never()).withholdOpen(any(), any(), any());
    }

    @Test
    void shouldRouteNoModelCallWhenTheRunHasNoRecordedDecision() {
        when(jobs.findByRunIdAndWorkspaceId(eq(fence.runId()), eq(fence.workspaceId())))
                .thenReturn(Optional.of(composing.job()));

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
    void shouldRestoreTheClaimDecisionOnAnotherReplicaWithoutRequeueingItsRun() {
        claim(CompositionStop.BUDGET);
        when(continuation.stageWithoutInputs(composing.job())).thenReturn(restored());
        var other = replica(new ReviewAttemptCustody(runs));

        other.prepare(run);

        assertThat(other.attempt(run)).isEmpty();
        verify(sessions).withholdOpen(composing.job(), composing.identity(), CompositionStop.BUDGET);
        verify(reviewRuns, never()).requeue(any(), any(), any(), any(), any());
    }

    @Test
    void shouldRefuseAnUnrecordedLegacyDecisionWithoutInventingALeaseLossOrRequeueing() {
        when(admission.composing(fence)).thenReturn(composing);

        assertThatThrownBy(() -> kind.prepare(run))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode().value()).isEqualTo(503));

        verify(reviewRuns, never()).requeue(any(), any(), any(), any(), any());
        verify(continuation, never()).restore(any());
        verify(sessions, never()).withholdOpen(any(), any(), any());
    }

    @Test
    void shouldCompleteTheJobAndReleaseItsInputsWhenTheRunCompletes() {
        claim(null);
        when(continuation.restore(composing.job())).thenReturn(restored());
        when(terminal.completeComposed(composing.identity().jobId(), fence)).thenReturn(true);
        kind.prepare(run);

        var logger = (Logger) LoggerFactory.getLogger(ReviewStageTelemetry.class);
        var appender = new ListAppender<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        appender.start();
        logger.addAppender(appender);
        var previous = MDC.getCopyOfContextMap();
        var gateway = Map.of(
                "workspace.id",
                "7",
                "review.id",
                composing.job().getReviewId().toString(),
                "run.id",
                fence.runId().toString(),
                "run.epoch",
                Integer.toString(fence.epoch()),
                "job.attempt",
                Integer.toString(ReviewRunKind.attempt(fence.epoch())),
                "subject.key",
                "review:1");
        MDC.setContextMap(gateway);
        try {
            assertThat(kind.end(run, SandboxRunKind.Outcome.COMPLETED)).isTrue();
            assertThat(MDC.getCopyOfContextMap()).isEqualTo(gateway);
        } finally {
            logger.detachAppender(appender);
            if (previous == null) MDC.clear();
            else MDC.setContextMap(previous);
        }
        assertThat(appender.list)
                .filteredOn(event -> fence.runId()
                        .toString()
                        .equals(event.getMDCPropertyMap().get("run.id")))
                .singleElement()
                .satisfies(event -> assertThat(event.getMDCPropertyMap())
                        .containsAllEntriesOf(gateway)
                        .containsEntry("job.id", composing.identity().jobId().toString()));

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
        when(reviewRuns.retryComposition(composing.identity().jobId(), fence)).thenReturn(true);

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
        when(started.job()).thenReturn(composing.job());
        when(claims.claimLeased(fence)).thenReturn(Optional.of(started));
        when(admission.composing(fence)).thenReturn(composing);
        when(jobs.findByRunIdAndWorkspaceId(eq(fence.runId()), eq(fence.workspaceId())))
                .thenReturn(Optional.of(composing.job()));
        assertThat(kind.claimed(run)).isTrue();
    }

    private ReviewCompositionContinuation.Restored restored() {
        return new ReviewCompositionContinuation.Restored(
                new PreparedJobInputs(Map.of(), List.of(), List.of(closed::incrementAndGet), null, null), Map.of());
    }
}
