package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.composer.CompositionHandedOnException;
import de.tum.cit.aet.hephaestus.agent.composer.CompositionStop;
import de.tum.cit.aet.hephaestus.agent.composer.WorkerFeedbackComposer;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.handler.DefaultReviewExecutionHandler;
import de.tum.cit.aet.hephaestus.agent.handler.EvidenceSnapshotFixtures;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ReviewSourceNotReadyException;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeAgentRequest;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.proxy.LlmCallPermits;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunLease;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunNotifications;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerCapacityState;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxTestLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInitializationException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxRoom;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetBlockReason;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetDecision;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.evidence.AppliesWhenCheck;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessDecision;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReport;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureFacts;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceReadinessCheck;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStart;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class ReviewJobExecutorTest extends BaseUnitTest {
    @TempDir
    Path folder;

    private final ReviewJobRepository jobs = mock(ReviewJobRepository.class);
    private final ReviewMemberAiPolicy policy = mock(ReviewMemberAiPolicy.class);
    private final DefaultReviewExecutionHandler handler = mock(DefaultReviewExecutionHandler.class);
    private final ReviewStart reviewStart = mock(ReviewStart.class);
    private final ReviewJobAssignments assignments = mock(ReviewJobAssignments.class);
    private final SandboxManager sandbox = mock(SandboxManager.class);
    private final LlmBudgetService budgets = mock(LlmBudgetService.class);
    private final PracticePiAdapter practiceAgent = mock(PracticePiAdapter.class);
    private final AttemptTokenIssuer jwtIssuer = mock(AttemptTokenIssuer.class);
    private Optional<WorkerCapacityState> capacity = Optional.empty();
    private SandboxProperties sandboxProperties = SandboxTestLimits.PROPERTIES;
    private int maxConcurrentCompositions = 16;
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final PracticeScriptRunRecorder practiceScriptRuns = mock(PracticeScriptRunRecorder.class);
    private final LlmUsageRecorder usageRecorder = mock(LlmUsageRecorder.class);
    private final ReviewAttemptUsageService attemptUsage =
            TerminalUsageTestFixtures.attemptUsageOverMocks(jobs, usageRecorder);
    private ReviewRunKind reviewRuns = mock(ReviewRunKind.class);
    private final AgentRuns runs = mock(AgentRuns.class);

    private static final String ADMITTED_RESULT =
            "{\"contractVersion\":3,\"results\":[{\"practiceSlug\":\"silent-failure\","
                    + "\"candidate\":{\"outcome\":\"NOT_MET\",\"summary\":\"s\",\"explanation\":\"e\","
                    + "\"citations\":[],\"problems\":[{\"severity\":\"MAJOR\",\"summary\":\"s\","
                    + "\"explanation\":\"e\",\"citations\":[]}]}}]}";
    private static final String SCRIPT_REPORT = "{\"schemaVersion\":2,\"truncated\":false,\"practices\":[]}";
    private static final AgentResult.LlmUsage RETURNED_USAGE =
            new AgentResult.LlmUsage("m", 1000, 700, 0, 0, 0, 0.0, 6);

    private AgentProperties properties() {
        return new AgentProperties(
                true,
                Duration.ofSeconds(1),
                5,
                Duration.ofSeconds(25),
                Duration.ofDays(14),
                Duration.ofDays(90),
                maxConcurrentCompositions);
    }

    /** The executor runs the attempt as the worker that claimed it, under the attempt's own claim. */
    private static ReviewJobExecutor tracking(ReviewJobExecutor executor, ReviewJob job) {
        executor.track(claimOf(job));
        return executor;
    }

    /** The claim of the job's latest attempt, numbered as its run's epoch. */
    private static ReviewJobClaimService.ClaimResult claimOf(ReviewJob job) {
        if (job.getRunId() == null) job.setRunId(UUID.randomUUID());
        var fence = new AgentRunFence(job.getRunId(), job.getWorkspaceId(), ReviewRunKind.epoch(job.getAttempt()));
        return new ReviewJobClaimService.ClaimResult(job, fence, TEST_SNAPSHOT, "pull_request");
    }

    private static final ConfigSnapshot TEST_SNAPSHOT = new ConfigSnapshot(
            ConfigSnapshot.SCHEMA_VERSION,
            new FrozenModel(
                    LlmApiProtocol.OPENAI_COMPLETIONS,
                    "https://api.example.com/v1",
                    "test-model",
                    UsageFundingSource.INSTANCE,
                    1L,
                    1L,
                    1L,
                    null,
                    null,
                    null,
                    false),
            null,
            null,
            600,
            null);

    /** The claim's next offer is the job's run, which leases it for the job's next attempt. */
    private void offer(ReviewJob job) {
        if (job.getRunId() == null) job.setRunId(UUID.randomUUID());
        UUID runId = job.getRunId();
        int epoch = ReviewRunKind.epoch(job.getAttempt());
        when(runs.claim(any(), any()))
                .thenAnswer(call -> {
                    return Optional.of(new AgentRunLease(
                            runId,
                            job.getWorkspaceId(),
                            AgentRunKind.REVIEW,
                            ReviewRunKind.subject(job.getReviewId()),
                            job.spentAttempts(),
                            epoch,
                            Instant.now().plusSeconds(300),
                            null));
                })
                .thenReturn(Optional.empty());
        lenient().when(jobs.lockQueuedByRun(runId)).thenReturn(Optional.of(job));
    }

    /** The claim leases a run whose job a lifecycle write holds. */
    private void offerHeld(ReviewJob job) {
        offer(job);
        when(jobs.lockQueuedByRun(job.getRunId())).thenReturn(Optional.empty());
    }

    private ReviewJobExecutor executor(AsyncTaskExecutor pool) {
        lenient().when(jobs.lockParentForQueuedClaim(any())).thenReturn(Optional.of(UUID.randomUUID()));
        lenient()
                .when(runs.capacity())
                .thenReturn(new AgentCapacityProperties(
                        Duration.ofMinutes(5),
                        Duration.ofSeconds(25),
                        5,
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(15)));
        lenient().when(reviewStart.start(anyLong(), any())).thenReturn(true);
        lenient().when(reviewStart.compose(anyLong(), any())).thenReturn(true);
        var transactions = mock(TransactionTemplate.class);
        lenient().when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        var telemetry = new ReviewJobTelemetry(meters, Tracer.NOOP);
        var callPermits = mock(LlmCallPermits.class);
        lenient().when(callPermits.poolFor(any())).thenReturn(1L);
        return new ReviewJobExecutor(
                properties(),
                jobs,
                handler,
                practiceAgent,
                jwtIssuer,
                sandbox,
                sandboxProperties,
                pool,
                transactions,
                new JsonMapper(),
                meters,
                telemetry,
                attemptUsage,
                practiceScriptRuns,
                null,
                capacity,
                new ReviewJobClaimService(
                        jobs,
                        policy,
                        mock(ReviewMethodVerifier.class),
                        assignments,
                        transactions,
                        new JsonMapper(),
                        reviewStart,
                        budgets,
                        null,
                        telemetry,
                        callPermits,
                        runs,
                        reviewRuns),
                reviewRuns,
                runs,
                mock(AgentRunNotifications.class));
    }

    @Test
    void shouldRequeueAnAttemptWhoseInitializerFailedBeforeAnyModelCall() {
        var job = configuredJob();
        reviewRuns = mock(ReviewRunKind.class);
        lenient().when(reviewRuns.mayRetry(any())).thenReturn(true);
        when(reviewRuns.requeue(any(), any(), eq(AgentRuns.Charge.SPEND), any(), any()))
                .thenReturn(true);

        assertThat(runFailingInitializer(job).processNext()).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);

        verify(reviewRuns).requeue(any(), any(), eq(AgentRuns.Charge.SPEND), any(), any());
        verify(jobs, never()).transitionStatusOwnedBy(any(), eq(ReviewJobStatus.FAILED), any(), any(), any(), anyInt());
        verify(jobs, never()).recordInitializationFailure(any(), anyLong(), anyInt(), any(), any());
        assertThat(requeued("sandbox")).isEqualTo(1);
        assertThat(requeued("preparation")).isZero();
    }

    @Test
    void shouldCountARequeueAsPreparationWhenTheSourceIsNotReadyBeforeTheSandboxStarts() {
        var job = configuredJob();
        reviewRuns = mock(ReviewRunKind.class);
        lenient().when(reviewRuns.mayRetry(any())).thenReturn(true);
        when(reviewRuns.requeue(any(), any(), eq(AgentRuns.Charge.SPEND), any(), any()))
                .thenReturn(true);
        offer(job);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(policy.permitsReview(job)).thenReturn(true);
        var binding = mock(WorkspaceAgentBinding.class);
        when(binding.getDataHandlingTier()).thenReturn(DataHandlingTier.UNDECLARED);
        when(policy.binding(job)).thenReturn(Optional.of(binding));
        when(budgets.decide(job.getWorkspaceId())).thenReturn(LlmBudgetDecision.ALLOWED);
        when(assignments.load(job)).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        when(handler.prepareInputs(job)).thenThrow(new ReviewSourceNotReadyException("not captured yet"));
        var pool = mock(AsyncTaskExecutor.class);
        doAnswer(invocation -> {
                    Runnable work = invocation.getArgument(0);
                    work.run();
                    return null;
                })
                .when(pool)
                .execute(any(Runnable.class));

        assertThat(executor(pool).processNext()).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);

        verify(sandbox, never()).execute(any());
        assertThat(requeued("preparation")).isEqualTo(1);
        assertThat(requeued("sandbox")).isZero();
    }

    @Test
    void shouldCountARequeueAsServerUnreachableWhenTheSandboxCouldNotReachTheGateway() {
        assertThat(requeuedAfterExit(SandboxLayout.EXIT_SERVER_UNREACHABLE, "server_unreachable"))
                .isEqualTo(1);
        assertThat(requeued("provider_unreachable")).isZero();
    }

    @Test
    void shouldCountARequeueAsProviderUnreachableWhenNoModelCallWasAnswered() {
        assertThat(requeuedAfterExit(SandboxLayout.EXIT_PROVIDER_UNREACHABLE, "provider_unreachable"))
                .isEqualTo(1);
        assertThat(requeued("server_unreachable")).isZero();
    }

    @Test
    void shouldCountARequeueAsASandboxFailureWhenASideOfThePodWasLost() {
        assertThat(requeuedAfterExit(SandboxLayout.EXIT_SIDE_LOST, "sandbox")).isEqualTo(1);
        assertThat(requeued("provider_unreachable")).isZero();
    }

    @Test
    void shouldEndAnAttemptWithoutARetryWhenTheServerAnsweredItsResultsWithAnError() {
        var job = configuredJob();
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setRunId(UUID.randomUUID());
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(handler.hasStaged(job)).thenReturn(true);
        when(handler.completeStaged(job)).thenReturn(false);
        when(handler.endedPractices(job)).thenReturn(0, 3);
        reviewRuns = mock(ReviewRunKind.class);
        lenient().when(reviewRuns.mayRetry(any())).thenReturn(true);
        var executor = runExecutor(
                mock(ReviewCompositionContinuation.class),
                new SandboxResult(SandboxLayout.EXIT_SERVER_ERROR, Map.of(), "", false, Duration.ofSeconds(1)),
                false);

        tracking(executor, job).executePrepared(job.getId(), job, mock(SandboxSpec.class));

        verify(handler).finishInterrupted(job, ReviewStopReason.EXECUTION_FAILED);
        verify(reviewRuns, never()).requeue(any(), any(), any(), any(), any());
        assertThat(meters.get("agent.job.server.error").counter().count()).isEqualTo(1);
        assertThat(requeued("sandbox")).isZero();
        assertThat(requeued("server_unreachable")).isZero();
        assertThat(stageCount("sandbox", "failed")).isEqualTo(1);
    }

    @Test
    void shouldTimeAReviewThatLeftPracticesOpenAsAPartialSandboxAndContinueIt() {
        var job = configuredJob();
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setRunId(UUID.randomUUID());
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(handler.hasStaged(job)).thenReturn(true);
        when(handler.completeStaged(job)).thenReturn(false);
        when(handler.endedPractices(job)).thenReturn(0, 16);
        reviewRuns = mock(ReviewRunKind.class);
        lenient().when(reviewRuns.mayRetry(any())).thenReturn(true);
        when(reviewRuns.requeue(any(), any(), eq(AgentRuns.Charge.FREE), any(), any()))
                .thenReturn(true);
        var executor = runExecutor(
                mock(ReviewCompositionContinuation.class),
                new SandboxResult(SandboxLayout.EXIT_PARTIAL_REVIEW, Map.of(), "", false, Duration.ofSeconds(1)),
                false);

        assertThat(tracking(executor, job).executePrepared(job.getId(), job, mock(SandboxSpec.class)))
                .isNull();

        verify(reviewRuns).requeue(eq(job.getId()), any(), eq(AgentRuns.Charge.FREE), any(), any());
        assertThat(stageCount("sandbox", "partial")).isEqualTo(1);
        assertThat(stageCount("sandbox", "failed")).isZero();
        assertThat(requeued("sandbox")).isZero();
    }

    private long stageCount(String stage, String outcome) {
        return meters.get("agent.job.stage.duration")
                .tags("stage", stage, "outcome", outcome)
                .timer()
                .count();
    }

    private double requeuedAfterExit(int exitCode, String cause) {
        var job = admittedJob();
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        reviewRuns = mock(ReviewRunKind.class);
        lenient().when(reviewRuns.mayRetry(any())).thenReturn(true);
        when(reviewRuns.requeue(any(), any(), eq(AgentRuns.Charge.SPEND), any(), any()))
                .thenReturn(true);
        var executor = runExecutor(
                mock(ReviewCompositionContinuation.class),
                new SandboxResult(exitCode, Map.of(), "", false, Duration.ofSeconds(1)),
                false);

        assertThat(tracking(executor, job).executePrepared(job.getId(), job, mock(SandboxSpec.class)))
                .isNull();
        return requeued(cause);
    }

    private double requeued(String cause) {
        return meters.get("agent.job.infra.retry.requeued")
                .tag("cause", cause)
                .counter()
                .count();
    }

    @Test
    void shouldFailTheJobAndRecordTheInitializersExitOnceWhenNoAttemptIsLeft() throws Exception {
        var job = configuredJob();
        reviewRuns = mock(ReviewRunKind.class);
        lenient().when(reviewRuns.mayRetry(any())).thenReturn(true);
        // The retry cap is spent: the run kind refuses another attempt.
        when(reviewRuns.mayRetry(any())).thenReturn(false);
        when(jobs.transitionStatusOwnedBy(
                        eq(job.getId()), eq(ReviewJobStatus.FAILED), any(), any(), any(UUID.class), anyInt()))
                .thenReturn(1);

        assertThat(runFailingInitializer(job).processNext()).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);

        var diagnosis = ArgumentCaptor.forClass(String.class);
        verify(jobs, times(1))
                .recordInitializationFailure(
                        eq(job.getId()),
                        eq(job.getWorkspaceId()),
                        eq(job.getAttempt()),
                        any(UUID.class),
                        diagnosis.capture());
        var recorded = new JsonMapper().readTree(diagnosis.getValue());
        assertThat(recorded.get("exitCode").asInt()).isEqualTo(2);
        assertThat(recorded.get("transcriptTail").asString()).contains("Cannot utime");
    }

    @Test
    void shouldCountAReviewThatTheDrainSentBackToTheQueue() {
        var job = configuredJob();
        reviewRuns = mock(ReviewRunKind.class);
        lenient().when(reviewRuns.mayRetry(any())).thenReturn(true);
        when(reviewRuns.requeue(eq(job.getId()), any(), eq(AgentRuns.Charge.FREE), any(), any()))
                .thenReturn(true);
        offer(job);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(policy.permitsReview(job)).thenReturn(true);
        var binding = mock(WorkspaceAgentBinding.class);
        when(binding.getDataHandlingTier()).thenReturn(DataHandlingTier.UNDECLARED);
        when(policy.binding(job)).thenReturn(Optional.of(binding));
        when(budgets.decide(job.getWorkspaceId())).thenReturn(LlmBudgetDecision.ALLOWED);
        when(assignments.load(job)).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        // Claimed, and still running: the pool has not started it when the worker drains.
        var executor = executor(mock(AsyncTaskExecutor.class));
        assertThat(executor.processNext()).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);

        assertThat(executor.cancelInFlight(ReviewJobCancellationReason.DRAIN_GRACEFUL, Duration.ofSeconds(1)))
                .isTrue();

        assertThat(meters.get("agent.job.drained")
                        .tag("outcome", "requeued")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    /** An attempt that reaches the sandbox, whose initializer then exits with 2. */
    @Test
    void shouldStarveNobodyWhileNoReviewWaitsForSandboxRoom() {
        var executor = executor(mock(AsyncTaskExecutor.class));
        Instant start = Instant.now();
        executor.endReviewsWithoutRoom(start);
        executor.endReviewsWithoutRoom(start.plus(Duration.ofHours(2)));

        // A review arrives at a runtime that has been full for two hours: its own hour starts now.
        when(jobs.existsQueuedForRoom()).thenReturn(true);
        executor.endReviewsWithoutRoom(start.plus(Duration.ofHours(3)));

        verify(jobs, never()).cancelQueuedForRoom(any(), any());
    }

    @Test
    void shouldKeepQueuedReviewsWhenTheQuotaIsUnobservedForTwoHours() {
        when(sandbox.roomFor(any())).thenReturn(SandboxRoom.UNKNOWN_ROOM);
        lenient().when(jobs.existsQueuedForRoom()).thenReturn(true);
        var executor = executor(mock(AsyncTaskExecutor.class));
        Instant start = Instant.now();

        executor.endReviewsWithoutRoom(start);
        executor.endReviewsWithoutRoom(start.plus(Duration.ofHours(2)));

        verify(jobs, never()).cancelQueuedForRoom(any(), any());
    }

    private ReviewJobExecutor runFailingInitializer(ReviewJob job) {
        when(sandbox.execute(any()))
                .thenThrow(new SandboxInitializationException(
                        2, false, "tar: work: Cannot utime: Operation not permitted\n"));
        return executorThatReachesTheSandbox(job);
    }

    private ReviewJobExecutor executorThatReachesTheSandbox(ReviewJob job) {
        offer(job);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(policy.permitsReview(job)).thenReturn(true);
        var binding = mock(WorkspaceAgentBinding.class);
        when(binding.getDataHandlingTier()).thenReturn(DataHandlingTier.UNDECLARED);
        when(policy.binding(job)).thenReturn(Optional.of(binding));
        when(budgets.decide(job.getWorkspaceId())).thenReturn(LlmBudgetDecision.ALLOWED);
        when(assignments.load(job)).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        when(handler.prepareInputs(job)).thenReturn(new PreparedJobInputs(Map.of(), List.of(), List.of(), null, null));
        when(jwtIssuer.issueForReview(eq(job.getId()), eq(job.getWorkspaceId()), anyInt(), any(), any(), any()))
                .thenReturn("issued-job-token");
        when(practiceAgent.buildSandboxSpec(any()))
                .thenReturn(new PracticeSandboxSpec(
                        "practice-runtime", List.of(), Map.of(), Map.of(), "issued-job-token", null));
        when(jobs.updateCapture(any(), anyLong(), any(), anyInt(), any())).thenReturn(1);
        when(jobs.markExecutionStarted(eq(job.getId()), any(UUID.class), anyInt(), any()))
                .thenReturn(1);
        var pool = mock(AsyncTaskExecutor.class);
        doAnswer(invocation -> {
                    Runnable work = invocation.getArgument(0);
                    work.run();
                    return null;
                })
                .when(pool)
                .execute(any(Runnable.class));
        return executor(pool);
    }

    @Test
    void shouldRecordTheInitializersExitAndItsMaskedTailForTheOperator() throws Exception {
        var failure = new SandboxInitializationException(
                2, false, "tar: work: Cannot utime: Operation not permitted\nAuthorization: Bearer secret-token\n");

        var diagnosis =
                new JsonMapper().readTree(ReviewJobExecutor.initializationDiagnosis(new JsonMapper(), 1, failure));

        assertThat(diagnosis.get("attempt").asInt()).isEqualTo(1);
        assertThat(diagnosis.get("exitCode").asInt()).isEqualTo(2);
        assertThat(diagnosis.get("timedOut").asBoolean()).isFalse();
        assertThat(diagnosis.get("transcriptTail").asString())
                .startsWith("tar: work: Cannot utime: Operation not permitted")
                .doesNotContain("secret-token");
        assertThat(new JsonMapper()
                        .readTree(ReviewJobExecutor.initializationDiagnosis(
                                new JsonMapper(), 0, new SandboxInitializationException(null, true, null)))
                        .get("exitCode")
                        .isNull())
                .isTrue();
    }

    @Test
    void shouldNotStartASandboxWhenALifecycleWriteHoldsTheClaimedJob() {
        offerHeld(RuntimeReviewTestData.job());
        assertThat(executor(mock(AsyncTaskExecutor.class)).processNext())
                .isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);
        verifyNoInteractions(handler, sandbox);
    }

    @Test
    void shouldCancelSuppressedWorkBeforeReadingOrRunningItsInputs() {
        var job = RuntimeReviewTestData.job();
        offer(job);
        when(policy.isProcessingSuppressed(job)).thenReturn(true);
        assertThat(executor(mock(AsyncTaskExecutor.class)).processNext())
                .isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);
        assertThat(job.getStatus()).isEqualTo(ReviewJobStatus.CANCELLED);
        assertThat(job.getCancellationReason()).isEqualTo(ReviewJobCancellationReason.PERSON_DATA_ERASED);
        var refused = Objects.requireNonNull(meters.find("practice.review.refused")
                .tag("phase", "execution")
                .tag("reason", "person_data_erased")
                .counter());
        assertThat(refused.count()).isEqualTo(1);
        verifyNoInteractions(handler, sandbox);
    }

    @Test
    void shouldCancelAReplacedReviewAtClaimWithNoSandbox() {
        var job = configuredJob();
        offer(job);
        when(policy.permitsReview(job)).thenReturn(true);
        when(reviewStart.replaceByNewerOccasion(job.getWorkspaceId(), job.getReviewId()))
                .thenReturn(true);

        assertThat(executor(mock(AsyncTaskExecutor.class)).processNext())
                .isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);

        assertThat(job.getStatus()).isEqualTo(ReviewJobStatus.CANCELLED);
        assertThat(job.getCancellationReason()).isEqualTo(ReviewJobCancellationReason.REPLACED);
        assertThat(job.getCompletedAt()).isNotNull();
        verify(runs).end(any());
        verify(reviewStart, never()).start(anyLong(), any());
        verifyNoInteractions(sandbox, budgets, assignments);
    }

    @Test
    void shouldNeverRunAnAlreadyAdmittedAttemptAgain() {
        var job = RuntimeReviewTestData.job();
        job.setAdmissionAttempt(0);
        job.setAdmissionDigest("admitted");
        offer(job);
        assertThat(executor(mock(AsyncTaskExecutor.class)).processNext())
                .isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);
        assertThat(job.getStatus()).isEqualTo(ReviewJobStatus.FAILED);
        verifyNoInteractions(handler, sandbox);
    }

    @Test
    void shouldCompleteARunThatFailedAfterItsResultsWereAdmitted() {
        var measured = admittedResult();
        var parsed = new PiResultParser(new JsonMapper(), meters).parse(measured);
        var admitted = RuntimeReviewTestData.job();
        admitted.setAdmissionAttempt(0);
        admitted.setAdmissionDigest("admitted");
        var executor = executor(mock(AsyncTaskExecutor.class));

        // The parser calls only exit 0 a success, so the admitted result keeps the run complete, not a failed exit.
        assertThat(parsed.success()).isFalse();
        assertThat(executor.determineTerminalStatus(measured, parsed, admitted)).isEqualTo(ReviewJobStatus.COMPLETED);
        assertThat(executor.determineTerminalStatus(measured, parsed, RuntimeReviewTestData.job()))
                .isEqualTo(ReviewJobStatus.FAILED);
    }

    @Test
    void shouldComposeAnAdmittedRunOnTheWorkerBeforeItsTerminalWriteAndForgetItsInputs() {
        var job = admittedJob();
        var kept = mock(ReviewCompositionContinuation.class);
        var composer = mock(WorkerFeedbackComposer.class);
        terminalWriteOf(job, ReviewJobStatus.COMPLETED);
        var executor = admittedExecutor(kept);
        executor.setFeedbackComposer(composer);

        assertThat(tracking(executor, job).executePrepared(job.getId(), job, mock(SandboxSpec.class)))
                .isEqualTo(ReviewJobStatus.COMPLETED);

        // The worker composes, then the attempt ends once.
        var order = inOrder(composer, jobs, kept);
        order.verify(composer).composeAfterMeasurement(eq(job), any(UUID.class), any());
        order.verify(jobs)
                .transitionStatusOwnedBy(
                        eq(job.getId()), eq(ReviewJobStatus.COMPLETED), any(), eq(null), any(UUID.class), anyInt());
        order.verify(kept).forget(job);
        verify(usageRecorder).project(eq(job.getWorkspaceId()), any());
        verify(practiceScriptRuns).record(eq(job), aryEq(scriptReport()), any(), any());
        // This run exits 1 although it admitted results, so its sandbox stage reads as failed.
        assertThat(stageCount("sandbox", "failed")).isEqualTo(1);
        assertThat(stageCount("composition", "ok")).isEqualTo(1);
    }

    @Test
    void shouldComposeATimedOutAdmittedRunAndEndItAsTimedOut() {
        var job = admittedJob();
        var kept = mock(ReviewCompositionContinuation.class);
        var composer = mock(WorkerFeedbackComposer.class);
        terminalWriteOf(job, ReviewJobStatus.TIMED_OUT);
        var executor = runExecutor(kept, timedOutResult(true), false);
        executor.setFeedbackComposer(composer);

        assertThat(tracking(executor, job).executePrepared(job.getId(), job, mock(SandboxSpec.class)))
                .isEqualTo(ReviewJobStatus.TIMED_OUT);

        verify(composer).composeAfterMeasurement(eq(job), any(UUID.class), any());
        assertThat(job.getExitCode()).isEqualTo(124);
        verify(kept).forget(job);
    }

    @Test
    void shouldHandOnAnAdmittedRunWithItsInputsKeptWhenItsCompositionFails() {
        var job = admittedJob();
        var kept = mock(ReviewCompositionContinuation.class);
        var composer = mock(WorkerFeedbackComposer.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(composer)
                .composeAfterMeasurement(any(), any(), any());
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        reviewRuns = mock(ReviewRunKind.class);
        lenient().when(reviewRuns.mayRetry(any())).thenReturn(true);
        var measured = new SandboxResult(
                0,
                Map.of(SandboxLayout.PRACTICE_SCRIPT_REPORT_FILE, scriptReport()),
                "done",
                false,
                Duration.ofSeconds(1));
        var executor = runExecutor(kept, measured, true);
        executor.setFeedbackComposer(composer);

        assertThat(tracking(executor, job).executePrepared(job.getId(), job, mock(SandboxSpec.class)))
                .isNull();
        // No later attempt reports the sandbox's practice-script runs, so the hand-on records them.
        verify(practiceScriptRuns).record(eq(job), aryEq(scriptReport()), any(), any());
        verify(reviewRuns).requeue(eq(job.getId()), any(), eq(AgentRuns.Charge.SPEND), any(), any());
        verify(jobs, never()).transitionStatusOwnedBy(any(), any(), any(), any(), any(), anyInt());
        verify(kept, never()).forget(any());
    }

    @Test
    void shouldComposeNothingForARunThatAdmittedNothing() {
        var job = admittedJob();
        job.setAdmissionAttempt(null);
        job.setAdmissionDigest(null);
        var composer = mock(WorkerFeedbackComposer.class);
        terminalWriteOf(job, ReviewJobStatus.FAILED);
        var executor = runExecutor(mock(ReviewCompositionContinuation.class), timedOutResult(false), false);
        executor.setFeedbackComposer(composer);

        assertThat(tracking(executor, job).executePrepared(job.getId(), job, mock(SandboxSpec.class)))
                .isEqualTo(ReviewJobStatus.FAILED);
        verifyNoInteractions(composer);
    }

    @Test
    void shouldSpendNoRetryWhenATimedOutAttemptEndedANewPractice() {
        var job = unfinishedStagedJob();
        when(handler.endedPractices(job)).thenReturn(2, 3);
        reviewRuns = mock(ReviewRunKind.class);
        lenient().when(reviewRuns.mayRetry(any())).thenReturn(true);
        when(reviewRuns.requeue(any(), any(), eq(AgentRuns.Charge.FREE), any(), any()))
                .thenReturn(true);
        var executor = runExecutor(mock(ReviewCompositionContinuation.class), timedOutResult(true), false);

        assertThat(tracking(executor, job).executePrepared(job.getId(), job, mock(SandboxSpec.class)))
                .isNull();

        verify(reviewRuns).requeue(any(), any(), eq(AgentRuns.Charge.FREE), any(), any());
        verify(reviewRuns, never()).requeue(any(), any(), eq(AgentRuns.Charge.SPEND), any(), any());
        verify(handler, never()).finishInterrupted(any(), any());
    }

    @Test
    void shouldSpendARetryWhenATimedOutAttemptEndedNoNewPractice() {
        var job = unfinishedStagedJob();
        when(handler.endedPractices(job)).thenReturn(3, 3);
        reviewRuns = mock(ReviewRunKind.class);
        lenient().when(reviewRuns.mayRetry(any())).thenReturn(true);
        when(reviewRuns.requeue(any(), any(), eq(AgentRuns.Charge.SPEND), any(), any()))
                .thenReturn(true);
        var executor = runExecutor(mock(ReviewCompositionContinuation.class), timedOutResult(true), false);

        assertThat(tracking(executor, job).executePrepared(job.getId(), job, mock(SandboxSpec.class)))
                .isNull();

        verify(reviewRuns, never()).requeue(any(), any(), eq(AgentRuns.Charge.FREE), any(), any());
        verify(reviewRuns).requeue(any(), any(), eq(AgentRuns.Charge.SPEND), any(), any());
    }

    /** A running attempt with staged practices and others still open. */
    private ReviewJob unfinishedStagedJob() {
        var job = configuredJob();
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setRunId(UUID.randomUUID());
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(handler.hasStaged(job)).thenReturn(true);
        when(handler.completeStaged(job)).thenReturn(false);
        return job;
    }

    private void terminalWriteOf(ReviewJob job, ReviewJobStatus status) {
        when(jobs.lockById(job.getId())).thenReturn(Optional.of(job));
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobs.transitionStatusOwnedBy(
                        eq(job.getId()), eq(status), any(), any(), any(UUID.class), eq(job.getAttempt())))
                .thenReturn(1);
    }

    private ReviewJobExecutor runExecutor(ReviewCompositionContinuation kept, SandboxResult result, boolean success) {
        var parsed = new AgentResult(success, Map.of("rawOutput", ADMITTED_RESULT), RETURNED_USAGE);
        when(sandbox.execute(any())).thenReturn(result);
        when(practiceAgent.parseResult(result)).thenReturn(parsed);
        var executor = executor(mock(AsyncTaskExecutor.class));
        executor.setCompositionContinuation(kept);
        return executor;
    }

    // The exit code a killed container reports; only the sandbox's timedOut fact says the sandbox stopped it.
    private static SandboxResult timedOutResult(boolean timedOut) {
        return new SandboxResult(
                124,
                Map.of(SandboxLayout.PRACTICE_SCRIPT_REPORT_FILE, scriptReport()),
                "stopped",
                timedOut,
                Duration.ofSeconds(1));
    }

    private ReviewJobExecutor admittedExecutor(ReviewCompositionContinuation kept) {
        var measured = admittedResult();
        when(sandbox.execute(any())).thenReturn(measured);
        when(practiceAgent.parseResult(measured))
                .thenReturn(new AgentResult(false, Map.of("rawOutput", ADMITTED_RESULT), RETURNED_USAGE));
        var executor = executor(mock(AsyncTaskExecutor.class));
        executor.setCompositionContinuation(kept);
        return executor;
    }

    private ReviewJob admittedJob() {
        var job = configuredJob();
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setRunId(UUID.randomUUID());
        job.setAdmissionAttempt(0);
        job.setAdmissionDigest("admitted");
        return job;
    }

    private ReviewJob configuredJob() {
        var job = RuntimeReviewTestData.job();
        job.setConfigSnapshot(new ConfigSnapshot(
                        ConfigSnapshot.SCHEMA_VERSION,
                        new FrozenModel(
                                LlmApiProtocol.OPENAI_COMPLETIONS,
                                "https://model.example",
                                "model",
                                null,
                                null,
                                null,
                                null,
                                DataHandlingTier.UNDECLARED,
                                null,
                                null,
                                false),
                        null,
                        null,
                        600,
                        null)
                .toJson(new JsonMapper()));
        return job;
    }

    @Test
    void shouldGiveTheSandboxTheConfiguredLimitsAndTheCredentialsDeadlineWhenPreparingAnAttempt() {
        var job = configuredJob();
        RuntimeJobTestData.attempt(job, 2);
        sandboxProperties = new SandboxProperties(5, 10, 60, new SandboxProperties.DefaultResourceLimits(1.5));
        var snapshot = ConfigSnapshot.fromJson(job.getConfigSnapshot(), new JsonMapper());
        offer(job);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(policy.permitsReview(job)).thenReturn(true);
        var binding = mock(WorkspaceAgentBinding.class);
        when(binding.getDataHandlingTier()).thenReturn(DataHandlingTier.UNDECLARED);
        when(policy.binding(job)).thenReturn(Optional.of(binding));
        when(budgets.decide(job.getWorkspaceId())).thenReturn(LlmBudgetDecision.ALLOWED);
        when(assignments.load(job)).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        when(handler.prepareInputs(job)).thenReturn(new PreparedJobInputs(Map.of(), List.of(), List.of(), null, null));
        when(jwtIssuer.issueForReview(eq(job.getId()), eq(job.getWorkspaceId()), eq(2), any(), any(), any()))
                .thenReturn("issued-job-token");
        when(practiceAgent.buildSandboxSpec(any()))
                .thenReturn(new PracticeSandboxSpec(
                        "practice-runtime", List.of(), Map.of(), Map.of(), "issued-job-token", null));
        when(jobs.updateCapture(any(), anyLong(), any(), anyInt(), any())).thenReturn(1);
        when(jobs.markExecutionStarted(eq(job.getId()), any(UUID.class), eq(2), any()))
                .thenReturn(1);
        var result = new SandboxResult(0, Map.of(), "", false, Duration.ZERO);
        when(sandbox.execute(any())).thenReturn(result);
        when(practiceAgent.parseResult(result)).thenReturn(new AgentResult(true, Map.of(), null));
        var pool = mock(AsyncTaskExecutor.class);
        doAnswer(invocation -> {
                    Runnable work = invocation.getArgument(0);
                    work.run();
                    return null;
                })
                .when(pool)
                .execute(any(Runnable.class));

        assertThat(executor(pool).processNext()).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);

        var issuedDeadline = ArgumentCaptor.forClass(Instant.class);
        var issuedExpiry = ArgumentCaptor.forClass(Instant.class);
        verify(jwtIssuer)
                .issueForReview(
                        eq(job.getId()),
                        eq(job.getWorkspaceId()),
                        eq(2),
                        any(),
                        issuedDeadline.capture(),
                        issuedExpiry.capture());
        var request = ArgumentCaptor.forClass(PracticeAgentRequest.class);
        verify(practiceAgent).buildSandboxSpec(request.capture());
        var execution = ArgumentCaptor.forClass(SandboxSpec.class);
        verify(sandbox).execute(execution.capture());
        var spec = execution.getValue();
        var sandboxDeadline = Objects.requireNonNull(spec.workDeadline());
        assertThat(issuedDeadline.getValue()).isEqualTo(sandboxDeadline);
        assertThat(issuedExpiry.getValue()).isEqualTo(sandboxDeadline.plus(SandboxLayout.RESULT_UPLOAD_GRACE));
        assertThat(request.getValue().jobToken()).isEqualTo("issued-job-token");
        assertThat(spec.jobId()).isEqualTo(job.getId());
        assertThat(spec.attempt()).isEqualTo(2);
        assertThat(spec.resourceLimits())
                .isEqualTo(new ResourceLimits(1.5, Duration.ofSeconds(snapshot.timeoutSeconds())));
    }

    private static byte[] scriptReport() {
        return SCRIPT_REPORT.getBytes(StandardCharsets.UTF_8);
    }

    private static SandboxResult admittedResult() {
        return new SandboxResult(
                1,
                Map.of(
                        "result.json",
                        ADMITTED_RESULT.getBytes(StandardCharsets.UTF_8),
                        SandboxLayout.PRACTICE_SCRIPT_REPORT_FILE,
                        scriptReport()),
                "measured",
                false,
                Duration.ofSeconds(1));
    }

    @BeforeEach
    void sandboxRuntimeReady() {
        when(sandbox.ready()).thenReturn(true);
        // A claim leases its attempt on the database clock.
        lenient().when(jobs.databaseNow()).thenAnswer(invocation -> Instant.now());
        lenient().when(sandbox.roomFor(any())).thenReturn(Integer.MAX_VALUE);
    }

    @Test
    void shouldClaimNothingWhenTheSandboxRuntimeHasNotProvedItsIsolation() {
        when(sandbox.ready()).thenReturn(false);

        assertThat(executor(mock(AsyncTaskExecutor.class)).computeCapacity())
                .as("queued reviews stay queued until the runtime starts sandboxes")
                .isZero();
    }

    @Test
    void shouldLeaveTheReviewQuotaToTheClaimSoCompositionsStayEligible() {
        // The claim checks the quota for a review Pod; a composition creates none. Its claim test covers the room.
        assertThat(executor(mock(AsyncTaskExecutor.class)).computeCapacity()).isPositive();
        verify(sandbox, never()).roomFor(any());
    }

    @Test
    void shouldClaimNoMoreThanTheSandboxSlots() {
        sandboxProperties = new SandboxProperties(2, 10, 60, new SandboxProperties.DefaultResourceLimits(1.5));
        assertThat(executor(mock(AsyncTaskExecutor.class)).computeCapacity()).isEqualTo(2);
    }

    @Test
    void shouldFreeTheSandboxSlotWhileComposingAndHandTheAttemptOnWhenTheWorkerDrains() {
        var job = continuedJob();
        reviewRuns = mock(ReviewRunKind.class);
        lenient().when(reviewRuns.mayRetry(any())).thenReturn(true);
        when(reviewRuns.requeue(eq(job.getId()), any(), eq(AgentRuns.Charge.FREE), any(), any()))
                .thenReturn(true);
        var executor = executor(inlinePool());
        var composer = mock(WorkerFeedbackComposer.class);
        var freeWhileComposing = new AtomicInteger(-1);
        var drained = new AtomicBoolean();
        doAnswer(invocation -> {
                    freeWhileComposing.set(executor.computeCapacity());
                    drained.set(executor.cancelInFlight(ReviewJobCancellationReason.DRAIN_GRACEFUL));
                    // The drain handed the attempt on, so the proxy fences the composer's next call.
                    throw new ResultAdmissionService.StaleAttemptException();
                })
                .when(composer)
                .composeResumed(any(), any(), any());
        executor.setFeedbackComposer(composer);

        assertThat(executor.processNext()).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);

        assertThat(freeWhileComposing.get()).isEqualTo(properties().claimBatchSize());
        assertThat(drained).isTrue();
        verify(reviewRuns).requeue(eq(job.getId()), any(), eq(AgentRuns.Charge.FREE), any(), any());
        assertThat(executor.computeCapacity()).isEqualTo(properties().claimBatchSize());
    }

    @Test
    void shouldClaimNothingWhileTheMostCompositionsRun() {
        maxConcurrentCompositions = 1;
        continuedJob();
        var executor = executor(inlinePool());
        var composer = mock(WorkerFeedbackComposer.class);
        var freeWhileComposing = new AtomicInteger(-1);
        doAnswer(invocation -> {
                    freeWhileComposing.set(executor.computeCapacity());
                    return null;
                })
                .when(composer)
                .composeResumed(any(), any(), any());
        executor.setFeedbackComposer(composer);

        assertThat(executor.processNext()).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);

        assertThat(freeWhileComposing.get()).isZero();
        assertThat(executor.computeCapacity()).isEqualTo(1);
    }

    @Test
    void shouldEndWithoutHandingOnWhenTheJobIsCancelledWhileItComposes() {
        var job = continuedJob();
        reviewRuns = mock(ReviewRunKind.class);
        lenient().when(reviewRuns.mayRetry(any())).thenReturn(true);
        var executor = executor(inlinePool());
        var composer = mock(WorkerFeedbackComposer.class);
        doAnswer(invocation -> {
                    // The cancellation ended the attempt; only its container, long gone, is stopped here.
                    assertThat(executor.cancelLocalJob(job.getId(), "cancelled"))
                            .isTrue();
                    throw new ResultAdmissionService.StaleAttemptException();
                })
                .when(composer)
                .composeResumed(any(), any(), any());
        executor.setFeedbackComposer(composer);

        assertThat(executor.processNext()).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);

        // The cancellation owns the outcome: nothing hands the attempt on, and its fenced terminal write changes
        // nothing.
        verify(reviewRuns, never()).requeue(any(), any(), any(), any(), any());
    }

    @Test
    void shouldHandOnAnAttemptWhoseCompositionAPassingCauseStopped() {
        var job = continuedJob();
        reviewRuns = mock(ReviewRunKind.class);
        lenient().when(reviewRuns.mayRetry(any())).thenReturn(true);
        var executor = executor(inlinePool());
        var composer = mock(WorkerFeedbackComposer.class);
        doThrow(new CompositionHandedOnException(CompositionStop.PROVIDER, null))
                .when(composer)
                .composeResumed(any(), any(), any());
        executor.setFeedbackComposer(composer);

        assertThat(executor.processNext()).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);

        verify(reviewRuns).requeue(eq(job.getId()), any(), eq(AgentRuns.Charge.SPEND), any(), any());
        verify(jobs, never()).transitionStatusOwnedBy(any(), any(), any(), any(), any(), anyInt());
        // A resumed attempt composes without a capture or a sandbox of its own.
        assertThat(stageCount("composition", "handed_on")).isEqualTo(1);
        assertThat(stageCount("sandbox", "ok") + stageCount("capture", "ok")).isZero();
    }

    @Test
    void shouldWithholdInsteadOfCancellingAHandedOnReviewWhoseDeveloperDeclinedAi() {
        var job = continuedJob();
        when(policy.permitsReview(job)).thenReturn(false);
        var executor = executor(inlinePool());
        var composer = mock(WorkerFeedbackComposer.class);
        executor.setFeedbackComposer(composer);

        assertThat(executor.processNext()).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);

        verify(composer).withholdResumed(any(), any(UUID.class), eq(CompositionStop.NO_AI));
        verify(composer, never()).composeResumed(any(), any(), any());
        assertThat(job.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
    }

    @Test
    void shouldKeepStagedResultsAndRecordModelUnavailableForTheRemainingPractices() {
        var job = configuredJob();
        RuntimeJobTestData.attempt(job, 1);
        offer(job);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(policy.permitsReview(job)).thenReturn(true);
        when(budgets.decide(job.getWorkspaceId())).thenReturn(LlmBudgetDecision.ALLOWED);
        when(assignments.load(job)).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        when(assignments.hasStaged(job)).thenReturn(true);
        when(handler.hasStaged(job)).thenReturn(true);
        when(jobs.markExecutionStarted(eq(job.getId()), any(UUID.class), eq(1), any()))
                .thenReturn(1);
        doAnswer(call -> {
                    job.setAdmissionAttempt(1);
                    job.setAdmissionDigest("sealed");
                    return null;
                })
                .when(handler)
                .finishInterrupted(job, ReviewStopReason.MODEL_UNAVAILABLE);
        var composer = mock(WorkerFeedbackComposer.class);
        var executor = executor(inlinePool());
        executor.setFeedbackComposer(composer);
        assertThat(executor.processNext()).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);
        verify(handler).finishInterrupted(job, ReviewStopReason.MODEL_UNAVAILABLE);
        verify(composer).withholdResumed(any(), eq(job.getRunId()), eq(CompositionStop.MODEL_UNAVAILABLE));
        verifyNoInteractions(sandbox);
    }

    @Test
    void shouldNeitherSealNorInterruptStagedWorkForTheClaimThatTookTheRow() {
        var job = configuredJob();
        RuntimeJobTestData.attempt(job, 1);
        offer(job);
        // By the time the attempt reads its row again, a later claim holds it under another lease.
        var taken = configuredJob();
        taken.setId(job.getId());
        RuntimeJobTestData.attempt(taken, 2);
        taken.setRunId(UUID.randomUUID());
        when(jobs.findById(job.getId())).thenReturn(Optional.of(taken));
        when(policy.permitsReview(job)).thenReturn(true);
        when(budgets.decide(job.getWorkspaceId())).thenReturn(LlmBudgetDecision.ALLOWED);
        when(assignments.load(job)).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        when(assignments.hasStaged(job)).thenReturn(true);
        var composer = mock(WorkerFeedbackComposer.class);
        var executor = executor(inlinePool());
        executor.setFeedbackComposer(composer);

        assertThat(executor.processNext()).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);

        verify(handler, never()).finishInterrupted(any(), any());
        verify(handler, never()).completeStaged(any());
        verifyNoInteractions(composer, sandbox);
    }

    @Test
    void shouldLeaveTheLaterAttemptsRowAloneWhenAClaimTakesItWhileTheSandboxRuns() {
        var job = configuredJob();
        RuntimeJobTestData.attempt(job, 1);
        var later = configuredJob();
        later.setId(job.getId());
        later.setStatus(ReviewJobStatus.RUNNING);
        RuntimeJobTestData.attempt(later, 2);
        UUID laterLease = UUID.randomUUID();
        later.setRunId(laterLease);
        var measured = new SandboxResult(0, Map.of(), "measured", false, Duration.ofSeconds(1));
        when(sandbox.execute(any())).thenAnswer(invocation -> {
            when(jobs.findById(job.getId())).thenReturn(Optional.of(later));
            return measured;
        });
        when(practiceAgent.parseResult(measured))
                .thenReturn(new AgentResult(true, Map.of("rawOutput", ADMITTED_RESULT), RETURNED_USAGE));
        // Stubbed as if unfenced: a write that passes the executor's own fence lands on the later row, where this
        // test sees it. Staged work gives a passing attempt something to seal or interrupt.
        lenient().when(jobs.lockById(job.getId())).thenReturn(Optional.of(later));
        lenient()
                .when(jobs.transitionStatusOwnedBy(any(), any(), any(), any(), any(UUID.class), anyInt()))
                .thenReturn(1);
        lenient().when(handler.hasStaged(later)).thenReturn(true);
        reviewRuns = mock(ReviewRunKind.class);
        lenient().when(reviewRuns.mayRetry(any())).thenReturn(true);
        var composer = mock(WorkerFeedbackComposer.class);
        var executor = executorThatReachesTheSandbox(job);
        executor.setFeedbackComposer(composer);

        assertThat(executor.processNext()).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);

        verify(sandbox).execute(any());
        assertThat(later.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(later.getAttempt()).isEqualTo(2);
        assertThat(later.getRunId()).isEqualTo(laterLease);
        assertThat(later.getOutput()).isNull();
        assertThat(later.getExitCode()).isNull();
        assertThat(later.getProcessingStatus()).isEqualTo(ProcessingStatus.NONE);
        verify(jobs, never()).transitionStatusOwnedBy(any(), any(), any(), any(), any(), anyInt());
        verify(jobs, never()).saveAndFlush(any());
        // Only the attempt's own check before the sandbox started.
        verify(handler).completeStaged(any());
        verify(handler, never()).finishInterrupted(any(), any());
        verify(reviewRuns, never()).requeue(any(), any(), any(), any(), any());
        verifyNoInteractions(composer);
    }

    /** An admitted job queued again to compose what its lost attempt left open, claimable by this worker. */
    private ReviewJob continuedJob() {
        var job = configuredJob();
        RuntimeJobTestData.attempt(job, 1);
        job.setAdmissionAttempt(0);
        job.setAdmissionDigest("admitted");
        offer(job);
        when(policy.permitsReview(job)).thenReturn(true);
        var binding = mock(WorkspaceAgentBinding.class);
        // A claim that withholds only reads no binding.
        lenient().when(binding.getDataHandlingTier()).thenReturn(DataHandlingTier.UNDECLARED);
        when(policy.binding(job)).thenReturn(Optional.of(binding));
        when(budgets.decide(job.getWorkspaceId())).thenReturn(LlmBudgetDecision.ALLOWED);
        when(assignments.load(job)).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        when(jobs.markExecutionStarted(eq(job.getId()), any(UUID.class), eq(1), any()))
                .thenReturn(1);
        // The claim committed: the row now runs here.
        var running = configuredJob();
        running.setId(job.getId());
        RuntimeJobTestData.attempt(running, 1);
        running.setAdmissionAttempt(0);
        running.setAdmissionDigest("admitted");
        running.setStatus(ReviewJobStatus.RUNNING);
        // The reloaded row carries the lease that the claim gave this attempt.
        when(jobs.findById(job.getId())).thenAnswer(invocation -> {
            running.setRunId(job.getRunId());
            return Optional.of(running);
        });
        return job;
    }

    private static AsyncTaskExecutor inlinePool() {
        var pool = mock(AsyncTaskExecutor.class);
        doAnswer(invocation -> {
                    Runnable work = invocation.getArgument(0);
                    work.run();
                    return null;
                })
                .when(pool)
                .execute(any(Runnable.class));
        return pool;
    }

    @Test
    void shouldStopTheLocalAttemptOfAJobBeforeItsNextClaimStartsHere() {
        var job = configuredJob();
        RuntimeJobTestData.attempt(job, 2);
        offer(job);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(policy.permitsReview(job)).thenReturn(true);
        var binding = mock(WorkspaceAgentBinding.class);
        when(binding.getDataHandlingTier()).thenReturn(DataHandlingTier.UNDECLARED);
        when(policy.binding(job)).thenReturn(Optional.of(binding));
        when(budgets.decide(job.getWorkspaceId())).thenReturn(LlmBudgetDecision.ALLOWED);
        when(assignments.load(job)).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        // The pool has not run attempt 3 yet, so it still runs here when its run leases the job again.
        var executor = executor(mock(AsyncTaskExecutor.class));
        assertThat(executor.processNext()).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);
        job.setStatus(ReviewJobStatus.QUEUED);
        offer(job);

        assertThat(executor.processNext()).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);

        verify(sandbox).cancel(job.getId());
        verify(jobs, never()).transitionStatusOwnedBy(any(), any(), any(), any(), any(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldStopTheLocalAttemptOfAJobWhoseRowThisWorkerNoLongerRuns(boolean stillRuns) {
        var job = configuredJob();
        offer(job);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(policy.permitsReview(job)).thenReturn(true);
        var binding = mock(WorkspaceAgentBinding.class);
        when(binding.getDataHandlingTier()).thenReturn(DataHandlingTier.UNDECLARED);
        when(policy.binding(job)).thenReturn(Optional.of(binding));
        when(budgets.decide(job.getWorkspaceId())).thenReturn(LlmBudgetDecision.ALLOWED);
        when(assignments.load(job)).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        var executor = executor(mock(AsyncTaskExecutor.class));
        assertThat(executor.processNext()).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);
        // A cancel, a requeue or a reap ended the attempt on its run alone, so its lease no longer renews.
        when(runs.renew(new AgentRunFence(job.getRunId(), job.getWorkspaceId(), ReviewRunKind.epoch(job.getAttempt()))))
                .thenReturn(stillRuns ? Optional.of(Instant.now().plusSeconds(300)) : Optional.empty());

        executor.stopEndedAttempts(executor.renewLeases());

        verify(sandbox, stillRuns ? never() : times(1)).cancel(job.getId());
    }

    @Test
    void shouldNotCancelAJobThisWorkerDoesNotOwn() {
        assertThat(executor(mock(AsyncTaskExecutor.class)).cancelLocalJob(UUID.randomUUID(), "cancelled"))
                .isFalse();
        verifyNoInteractions(sandbox);
    }

    @Test
    void shouldRenewEachLocalAttemptsRunThroughEveryPhaseAsItsOnlyWriter() {
        var job = configuredJob();
        var executor = tracking(executor(mock(AsyncTaskExecutor.class)), job);
        var fence = new AgentRunFence(job.getRunId(), job.getWorkspaceId(), ReviewRunKind.epoch(job.getAttempt()));
        when(runs.renew(fence)).thenReturn(Optional.of(Instant.now().plusSeconds(300)));

        assertThat(executor.renewLeases()).isEmpty();

        verify(runs).renew(fence);
        verifyNoInteractions(sandbox);
    }

    @Test
    void shouldStopRenewingAnAttemptPastItsTimeLimitSoItsLeaseExpires() {
        var job = configuredJob();
        var executor = tracking(executor(mock(AsyncTaskExecutor.class)), job);
        executor.clock = Clock.offset(
                Clock.systemUTC(),
                Duration.ofSeconds(TEST_SNAPSHOT.timeoutSeconds())
                        .plus(ReviewJobExecutor.ATTEMPT_GRACE)
                        .plusMinutes(1));

        assertThat(executor.renewLeases()).containsKey(job.getId());

        verify(runs, never()).renew(any());
    }

    @Test
    void shouldFinishAnEmptyDrainWithoutCallingTheProvider() {
        var executor = executor(mock(AsyncTaskExecutor.class));
        executor.stopAcceptingNewJobs();
        assertThat(executor.awaitInFlight(Duration.ZERO)).isTrue();
        assertThat(executor.cancelInFlight(ReviewJobCancellationReason.DRAIN_GRACEFUL))
                .isTrue();
        verifyNoInteractions(sandbox);
    }

    @Test
    void shouldFailMissingAssignmentsBeforeClaimingInsteadOfRetryingForever() {
        var job = RuntimeReviewTestData.job();
        offer(job);
        when(policy.isProcessingSuppressed(job))
                .thenThrow(new JobPreparationException(
                        JobPreparationException.Reason.INPUT_CONTRACT_INVALID, "private SQL/path"));
        assertThat(executor(mock(AsyncTaskExecutor.class)).processNext())
                .isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);
        assertThat(job.getStatus()).isEqualTo(ReviewJobStatus.FAILED);
        assertThat(job.getErrorMessage()).doesNotContain("private SQL/path");
        verify(jobs).save(job);
        verifyNoInteractions(handler, sandbox);
    }

    @Test
    void shouldCancelOldBudgetHeldWorkEvenAfterItsAvailabilityWasMoved() {
        var job = RuntimeReviewTestData.job();
        job.setSubmittedAt(Instant.now().minus(Duration.ofDays(8)));
        offer(job);
        when(policy.permitsReview(job)).thenReturn(true);
        when(budgets.decide(job.getWorkspaceId()))
                .thenReturn(new LlmBudgetDecision(LlmBudgetBlockReason.EXHAUSTED, LlmBudgetBlockReason.NONE));
        assertThat(executor(mock(AsyncTaskExecutor.class)).processNext())
                .isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);
        assertThat(job.getStatus()).isEqualTo(ReviewJobStatus.CANCELLED);
        assertThat(job.getCancellationReason()).isEqualTo(ReviewJobCancellationReason.BUDGET_EXHAUSTED);
        verifyNoInteractions(handler, sandbox);
    }

    @Test
    void shouldMeasureAgeFromSubmissionAfterARepeatedHold() {
        var job = RuntimeReviewTestData.job();
        job.setSubmittedAt(Instant.now().minus(Duration.ofDays(8)));
        assertThat(ReviewJobTelemetry.age(job)).isGreaterThanOrEqualTo(Duration.ofDays(8));
    }

    @Test
    void shouldReleaseCapacityAndMdcWhenInputPreparationFails() {
        var job = RuntimeReviewTestData.job();
        job.setSubmittedAt(Instant.now().minusSeconds(2));
        job.setConfigSnapshot(new ConfigSnapshot(
                        ConfigSnapshot.SCHEMA_VERSION,
                        new FrozenModel(
                                LlmApiProtocol.OPENAI_COMPLETIONS,
                                "https://model.example",
                                "model",
                                null,
                                null,
                                null,
                                null,
                                DataHandlingTier.UNDECLARED,
                                null,
                                null,
                                false),
                        null,
                        null,
                        600,
                        null)
                .toJson(new JsonMapper()));
        sandboxProperties = new SandboxProperties(1, 10, 60, new SandboxProperties.DefaultResourceLimits(1.5));
        var workerCapacity = mock(WorkerCapacityState.class);
        capacity = Optional.of(workerCapacity);
        offer(job);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobs.transitionStatusOwnedBy(any(), any(), any(), any(), any(UUID.class), anyInt()))
                .thenReturn(1);
        when(policy.permitsReview(job)).thenReturn(true);
        var binding = mock(WorkspaceAgentBinding.class);
        when(binding.getDataHandlingTier()).thenReturn(DataHandlingTier.UNDECLARED);
        when(policy.binding(job)).thenReturn(Optional.of(binding));
        when(budgets.decide(job.getWorkspaceId())).thenReturn(LlmBudgetDecision.ALLOWED);
        when(assignments.load(job)).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        when(handler.prepareInputs(job)).thenThrow(new JobPreparationException("Input preparation failed"));
        var pool = mock(AsyncTaskExecutor.class);
        doAnswer(invocation -> {
                    Runnable work = invocation.getArgument(0);
                    work.run();
                    return null;
                })
                .when(pool)
                .execute(any(Runnable.class));
        var executor = executor(pool);
        var logger = (Logger) LoggerFactory.getLogger(ReviewJobExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertThat(executor.processNext()).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(executor.computeCapacity()).isEqualTo(1);
        verify(workerCapacity).releaseReview();
        assertThat(MDC.get("job.id")).isNull();
        verify(sandbox, never()).execute(any());
        // A preparation failure names its stage, kind and exception types in the log line, never its message.
        assertThat(appender.list)
                .map(ILoggingEvent::getFormattedMessage)
                .anySatisfy(line -> assertThat(line)
                        .startsWith("Review preparation failed: jobId=" + job.getId())
                        .contains("stage=INPUTS", "kind=INPUT_PREPARATION")
                        .contains("exceptionTypes=[" + JobPreparationException.class.getName())
                        .doesNotContain("Input preparation failed"));
    }

    @Test
    void shouldResumeAnAdmittedReviewOnTheWorkerWithoutStartingASandbox() {
        var job = configuredJob();
        job.setSubmittedAt(Instant.now().minusSeconds(2));
        RuntimeJobTestData.attempt(job, 1);
        job.setAdmissionAttempt(0);
        job.setAdmissionDigest("admitted");
        offer(job);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        // The claim hands the row to this worker; the mock repository returns the same entity.
        when(jobs.markExecutionStarted(eq(job.getId()), any(UUID.class), eq(1), any()))
                .thenAnswer(invocation -> {
                    job.setExecutionStartedAt(invocation.getArgument(3));
                    return 1;
                });
        when(jobs.transitionStatusOwnedBy(
                        eq(job.getId()), eq(ReviewJobStatus.COMPLETED), any(), eq(null), any(UUID.class), eq(1)))
                .thenReturn(1);
        when(policy.permitsReview(job)).thenReturn(true);
        var binding = mock(WorkspaceAgentBinding.class);
        when(binding.getDataHandlingTier()).thenReturn(DataHandlingTier.UNDECLARED);
        when(policy.binding(job)).thenReturn(Optional.of(binding));
        when(budgets.decide(job.getWorkspaceId())).thenReturn(LlmBudgetDecision.ALLOWED);
        when(assignments.load(job)).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        var pool = mock(AsyncTaskExecutor.class);
        doAnswer(invocation -> {
                    Runnable work = invocation.getArgument(0);
                    work.run();
                    return null;
                })
                .when(pool)
                .execute(any(Runnable.class));
        var composer = mock(WorkerFeedbackComposer.class);
        var kept = mock(ReviewCompositionContinuation.class);
        var executor = executor(pool);
        executor.setFeedbackComposer(composer);
        executor.setCompositionContinuation(kept);

        assertThat(executor.processNext()).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);

        // It measures nothing again: no inputs are captured and no sandbox starts; it composes, then completes.
        verifyNoInteractions(handler, sandbox);
        verify(composer).composeResumed(eq(job), any(UUID.class), any());
        verify(jobs)
                .transitionStatusOwnedBy(
                        eq(job.getId()), eq(ReviewJobStatus.COMPLETED), any(), eq(null), any(UUID.class), anyInt());
        assertThat(job.getProcessingStatus()).isEqualTo(ProcessingStatus.PENDING);
        verify(kept).forget(job);
    }

    @Test
    void shouldCompleteARetryWhoseEarlierAttemptKeptEveryResultWithNoCapture() {
        var job = configuredJob();
        RuntimeJobTestData.attempt(job, 1);
        offer(job);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobs.transitionStatusOwnedBy(any(), any(), any(), any(), any(UUID.class), anyInt()))
                .thenReturn(1);
        when(policy.permitsReview(job)).thenReturn(true);
        var binding = mock(WorkspaceAgentBinding.class);
        when(binding.getDataHandlingTier()).thenReturn(DataHandlingTier.UNDECLARED);
        when(policy.binding(job)).thenReturn(Optional.of(binding));
        when(budgets.decide(job.getWorkspaceId())).thenReturn(LlmBudgetDecision.ALLOWED);
        when(assignments.load(job)).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        when(handler.keptEveryResult(job)).thenReturn(true);

        assertThat(executor(inlinePool()).processNext()).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);

        verify(handler).completeWithoutExecution(job);
        verify(handler, never()).prepareInputs(any());
        verify(jobs)
                .transitionStatusOwnedBy(
                        eq(job.getId()), eq(ReviewJobStatus.COMPLETED), any(), eq(null), any(UUID.class), eq(1));
        verify(sandbox, never()).execute(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldCompleteWithoutStartingASandboxWhenNoPracticeIsLeftToRun(boolean keepsItsResult) throws Exception {
        var job = RuntimeReviewTestData.job();
        job.setSubmittedAt(Instant.now().minusSeconds(2));
        job.setConfigSnapshot(new ConfigSnapshot(
                        ConfigSnapshot.SCHEMA_VERSION,
                        new FrozenModel(
                                LlmApiProtocol.OPENAI_COMPLETIONS,
                                "https://model.example",
                                "model",
                                null,
                                null,
                                null,
                                null,
                                DataHandlingTier.UNDECLARED,
                                null,
                                null,
                                false),
                        null,
                        null,
                        600,
                        null)
                .toJson(new JsonMapper()));
        sandboxProperties = new SandboxProperties(1, 10, 60, new SandboxProperties.DefaultResourceLimits(1.5));
        var workerCapacity = mock(WorkerCapacityState.class);
        capacity = Optional.of(workerCapacity);
        offer(job);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobs.transitionStatusOwnedBy(any(), any(), any(), any(), any(UUID.class), anyInt()))
                .thenReturn(1);
        when(policy.permitsReview(job)).thenReturn(true);
        var binding = mock(WorkspaceAgentBinding.class);
        when(binding.getDataHandlingTier()).thenReturn(DataHandlingTier.UNDECLARED);
        when(policy.binding(job)).thenReturn(Optional.of(binding));
        when(budgets.decide(job.getWorkspaceId())).thenReturn(LlmBudgetDecision.ALLOWED);
        when(assignments.load(job)).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        var now = Instant.now();
        var source = new SourceKind("scm.pull-request.diff");
        var contract = new SourceContractVersion("3.0.0");
        var readiness = new AutomatedReviewReadinessDecision(
                "clear-names",
                now,
                true,
                List.of(),
                List.of(new SourceReadinessCheck(source, contract, now, now, true, List.of())));
        var report = keepsItsResult
                ? new AutomatedReviewReadinessReport(
                                contract, "a".repeat(64), "scm.pull_request", now, now, List.of(readiness), Map.of())
                        .withReused(Set.of("clear-names"))
                : new AutomatedReviewReadinessReport(
                        contract,
                        "a".repeat(64),
                        "scm.pull_request",
                        now,
                        now,
                        List.of(readiness),
                        Map.of(
                                "clear-names",
                                new AppliesWhenCheck(
                                        true,
                                        "The change touches no Swift file.",
                                        List.of(new AppliesWhenCheck.Clause(
                                                "CHANGED_PATH", source, AppliesWhenCheck.Result.NOT_FOUND)))));
        var manifest = folder.resolve("manifest.json");
        var index = new JobFolderIndex(
                contract,
                "a".repeat(64),
                "scm.pull_request",
                now,
                List.of(new SourceCapture(
                        source,
                        new SourceCaptureState.Available(
                                SourceContentState.EMPTY,
                                SourceCompleteness.COMPLETE,
                                new SourceCaptureFacts(now, null, null, "base:head")),
                        EvidenceSnapshotFixtures.citation(source),
                        List.of())),
                List.of(),
                List.of(),
                List.of());
        Files.write(manifest, new JsonMapper().writeValueAsBytes(index));
        when(handler.prepareInputs(job))
                .thenReturn(new PreparedJobInputs(
                        Map.of(SandboxLayout.MANIFEST_PATH, manifest), List.of(), List.of(), index, report));
        when(jobs.updateCapture(any(), anyLong(), any(), anyInt(), any())).thenReturn(1);
        var pool = mock(AsyncTaskExecutor.class);
        doAnswer(invocation -> {
                    Runnable work = invocation.getArgument(0);
                    work.run();
                    return null;
                })
                .when(pool)
                .execute(any(Runnable.class));
        var executor = executor(pool);

        assertThat(executor.processNext()).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);

        assertThat(executor.computeCapacity()).isEqualTo(1);
        assertThat(job.getProcessingStatus()).isEqualTo(ProcessingStatus.PENDING);
        verify(jobs)
                .transitionStatusOwnedBy(
                        eq(job.getId()),
                        eq(ReviewJobStatus.COMPLETED),
                        any(),
                        eq(null),
                        any(UUID.class),
                        eq(job.getAttempt()));
        verify(handler).completeWithoutExecution(job);
        verify(workerCapacity).releaseReview();
        assertThat(MDC.get("job.id")).isNull();
        verify(sandbox, never()).execute(any());
    }
}
