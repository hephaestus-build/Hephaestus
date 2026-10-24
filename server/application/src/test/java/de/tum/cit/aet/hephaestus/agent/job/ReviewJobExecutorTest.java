package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.handler.DefaultReviewExecutionHandler;
import de.tum.cit.aet.hephaestus.agent.handler.EvidenceSnapshotFixtures;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeAgentRequest;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerCapacityState;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetBlockReason;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetDecision;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
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
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
    private final ReviewJobAssignments assignments = mock(ReviewJobAssignments.class);
    private final SandboxManager sandbox = mock(SandboxManager.class);
    private final LlmBudgetService budgets = mock(LlmBudgetService.class);
    private final PracticePiAdapter practiceAgent = mock(PracticePiAdapter.class);
    private final WorkerJwtIssuer jwtIssuer = mock(WorkerJwtIssuer.class);
    private Optional<WorkerCapacityState> capacity = Optional.empty();
    private Optional<WorkerProperties> worker = Optional.empty();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final PracticeScriptRunRecorder practiceScriptRuns = mock(PracticeScriptRunRecorder.class);
    private final LlmUsageRecorder usageRecorder = mock(LlmUsageRecorder.class);
    private final ReviewAttemptUsageService attemptUsage =
            TerminalUsageTestFixtures.attemptUsageOverMocks(jobs, usageRecorder);
    private ReviewJobRecovery recovery = new ReviewJobRecovery(
            jobs,
            properties(),
            new JsonMapper(),
            TerminalUsageTestFixtures.attemptUsageOverMocks(jobs, mock(LlmUsageRecorder.class)),
            Optional.empty(),
            Optional.empty());

    private static final String OWED_RESULT =
            "{\"contractVersion\":3,\"results\":[{\"practiceSlug\":\"silent-failure\","
                    + "\"candidate\":{\"outcome\":\"NOT_MET\",\"summary\":\"s\",\"explanation\":\"e\","
                    + "\"citations\":[],\"problems\":[{\"severity\":\"MAJOR\",\"summary\":\"s\","
                    + "\"explanation\":\"e\",\"citations\":[]}]}}]}";
    private static final String SCRIPT_REPORT = "{\"schemaVersion\":2,\"truncated\":false,\"practices\":[]}";
    private static final String FEEDBACK_REPORT =
            "{\"admissionDigest\":\"admitted\",\"compositionFailures\":[{\"phase\":\"PUBLIC_REVIEW\",\"reason\":\"STALL\"}]}";
    private static final AgentResult.LlmUsage RETURNED_USAGE =
            new AgentResult.LlmUsage("m", 1000, 700, 0, 0, 0, 0.0, 6);

    private static AgentProperties properties() {
        return new AgentProperties(
                true, Duration.ofSeconds(1), 5, 5, Duration.ofSeconds(25), Duration.ofDays(14), Duration.ofDays(90));
    }

    private ReviewJobExecutor executor(AsyncTaskExecutor pool) {
        var transactions = mock(TransactionTemplate.class);
        lenient().when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        var telemetry = new ReviewJobTelemetry(meters, Tracer.NOOP);
        return new ReviewJobExecutor(
                properties(),
                jobs,
                handler,
                practiceAgent,
                jwtIssuer,
                sandbox,
                pool,
                transactions,
                new JsonMapper(),
                meters,
                telemetry,
                attemptUsage,
                practiceScriptRuns,
                null,
                capacity,
                worker,
                new ReviewJobClaimService(
                        jobs,
                        policy,
                        mock(ReviewMethodVerifier.class),
                        assignments,
                        transactions,
                        new JsonMapper(),
                        meters,
                        budgets,
                        null,
                        telemetry,
                        Optional.empty()),
                recovery);
    }

    @Test
    void shouldNotStartASandboxAfterAnotherWorkerClaimedTheJob() {
        UUID id = UUID.randomUUID();
        when(jobs.findByIdQueuedForUpdateSkipLocked(any(), any())).thenReturn(Optional.empty());
        assertThat(executor(mock(AsyncTaskExecutor.class)).processJob(id))
                .isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);
        verifyNoInteractions(handler, sandbox);
    }

    @Test
    void shouldCancelSuppressedWorkBeforeReadingOrRunningItsInputs() {
        var job = RuntimeReviewTestData.job();
        when(jobs.findByIdQueuedForUpdateSkipLocked(any(), any())).thenReturn(Optional.of(job));
        when(policy.isProcessingSuppressed(job)).thenReturn(true);
        assertThat(executor(mock(AsyncTaskExecutor.class)).processJob(job.getId()))
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
    void shouldNeverRunAnAlreadyAdmittedAttemptAgain() {
        var job = RuntimeReviewTestData.job();
        job.setAdmissionAttempt(0);
        job.setAdmissionDigest("admitted");
        when(jobs.findByIdQueuedForUpdateSkipLocked(any(), any())).thenReturn(Optional.of(job));
        assertThat(executor(mock(AsyncTaskExecutor.class)).processJob(job.getId()))
                .isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);
        assertThat(job.getStatus()).isEqualTo(ReviewJobStatus.FAILED);
        verifyNoInteractions(handler, sandbox);
    }

    @Test
    void shouldCompleteAnAdmittedRunThatOwesPrivateFeedbackWhenNoAttemptContinuesIt() {
        var owed = owedResult();
        var parsed = new PiResultParser(new JsonMapper(), meters).parse(owed);
        var admitted = RuntimeReviewTestData.job();
        admitted.setAdmissionAttempt(0);
        admitted.setAdmissionDigest("admitted");
        var executor = executor(mock(AsyncTaskExecutor.class));

        // The parser calls only exit 0 a success, so the admitted result keeps the run complete, not a failed exit.
        assertThat(parsed.success()).isFalse();
        assertThat(executor.determineTerminalStatus(owed, parsed, admitted)).isEqualTo(ReviewJobStatus.COMPLETED);
        assertThat(executor.determineTerminalStatus(owed, parsed, RuntimeReviewTestData.job()))
                .isEqualTo(ReviewJobStatus.FAILED);
    }

    @Test
    void shouldContinueAnOwedRunWithItsReturnedUsageAndNeitherEndNorForgetIt() {
        var job = owedJob();
        var kept = mock(ReviewCompositionContinuation.class);
        recovery = mock(ReviewJobRecovery.class);
        when(recovery.continueComposition(
                        eq(job), eq("worker-1"), eq(RETURNED_USAGE), aryEq(scriptReport()), aryEq(feedbackReport())))
                .thenReturn(true);
        var executor = owedExecutor(kept);

        assertThat(executor.executePrepared(job.getId(), job, mock(SandboxSpec.class)))
                .isNull();

        // The returned usage, practice script report and feedback report go to the requeue; no terminal write ends the
        // job, and its inputs stay.
        verify(recovery)
                .continueComposition(
                        eq(job), eq("worker-1"), eq(RETURNED_USAGE), aryEq(scriptReport()), aryEq(feedbackReport()));
        assertThat(job.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(job.getCompletedAt()).isNull();
        assertThat(job.getExitCode()).isNull();
        verify(jobs, never()).transitionStatusOwnedBy(any(), any(), any(), any(), any(), any());
        verify(practiceScriptRuns, never()).record(any(), any(), any(), any());
        verify(usageRecorder, never()).project(any(), any());
        verify(kept, never()).forget(any());
    }

    @Test
    void shouldEndARefusedOwedRunLikeAnyAdmittedRunAndForgetItsInputs() {
        var job = owedJob();
        var kept = mock(ReviewCompositionContinuation.class);
        recovery = mock(ReviewJobRecovery.class);
        when(recovery.continueComposition(
                        eq(job), eq("worker-1"), eq(RETURNED_USAGE), aryEq(scriptReport()), aryEq(feedbackReport())))
                .thenReturn(false);
        when(jobs.lockById(job.getId())).thenReturn(Optional.of(job));
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobs.transitionStatusOwnedBy(
                        eq(job.getId()), eq(ReviewJobStatus.COMPLETED), any(), eq(null), any(), eq("worker-1")))
                .thenReturn(1);
        var executor = owedExecutor(kept);

        assertThat(executor.executePrepared(job.getId(), job, mock(SandboxSpec.class)))
                .isEqualTo(ReviewJobStatus.COMPLETED);

        assertThat(job.getExitCode()).isEqualTo(SandboxLayout.EXIT_COMPOSITION_OWED);
        assertThat(job.getProcessingStatus()).isEqualTo(ProcessingStatus.PENDING);
        // The ordinary terminal write bills the attempt and records its practice script runs, once each.
        verify(usageRecorder).project(eq(job.getWorkspaceId()), any());
        verify(practiceScriptRuns).record(eq(job), aryEq(scriptReport()), any(), any());
        verify(kept).forget(job);
    }

    @Test
    void shouldOfferATimedOutRunForContinuationAndNeitherEndNorForgetItWhenItContinues() {
        var job = owedJob();
        var kept = mock(ReviewCompositionContinuation.class);
        recovery = mock(ReviewJobRecovery.class);
        // The sandbox stopped the run: it wrote no feedback report, only its practice script report.
        when(recovery.continueComposition(eq(job), eq("worker-1"), eq(RETURNED_USAGE), aryEq(scriptReport()), isNull()))
                .thenReturn(true);
        var executor = runExecutor(kept, timedOutResult(true), false);

        assertThat(executor.executePrepared(job.getId(), job, mock(SandboxSpec.class)))
                .isNull();

        assertThat(job.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(job.getCompletedAt()).isNull();
        assertThat(job.getExitCode()).isNull();
        verify(jobs, never()).transitionStatusOwnedBy(any(), any(), any(), any(), any(), any());
        verify(usageRecorder, never()).project(any(), any());
        verify(kept, never()).forget(any());
    }

    @Test
    void shouldEndATimedOutRunWhoseContinuationIsRefusedAsTimedOutAndForgetItsInputs() {
        var job = owedJob();
        var kept = mock(ReviewCompositionContinuation.class);
        recovery = mock(ReviewJobRecovery.class);
        when(recovery.continueComposition(eq(job), eq("worker-1"), eq(RETURNED_USAGE), aryEq(scriptReport()), isNull()))
                .thenReturn(false);
        terminalWriteOf(job, ReviewJobStatus.TIMED_OUT);
        var executor = runExecutor(kept, timedOutResult(true), false);

        assertThat(executor.executePrepared(job.getId(), job, mock(SandboxSpec.class)))
                .isEqualTo(ReviewJobStatus.TIMED_OUT);

        assertThat(job.getExitCode()).isEqualTo(124);
        verify(jobs)
                .transitionStatusOwnedBy(
                        eq(job.getId()),
                        eq(ReviewJobStatus.TIMED_OUT),
                        any(),
                        eq("The container timed out."),
                        any(),
                        eq("worker-1"));
        verify(usageRecorder).project(eq(job.getWorkspaceId()), any());
        verify(practiceScriptRuns).record(eq(job), aryEq(scriptReport()), any(), any());
        verify(kept).forget(job);
    }

    @Test
    void shouldNotOfferAnExitWithTheTimeoutCodeForContinuationWhenTheSandboxDidNotTimeItOut() {
        var job = owedJob();
        var kept = mock(ReviewCompositionContinuation.class);
        recovery = mock(ReviewJobRecovery.class);
        terminalWriteOf(job, ReviewJobStatus.COMPLETED);
        var executor = runExecutor(kept, timedOutResult(false), false);

        assertThat(executor.executePrepared(job.getId(), job, mock(SandboxSpec.class)))
                .isEqualTo(ReviewJobStatus.COMPLETED);

        verify(recovery, never()).continueComposition(any(), any(), any(), any(), any());
        verify(kept).forget(job);
    }

    @Test
    void shouldNotOfferACleanExitForContinuation() {
        var job = owedJob();
        var kept = mock(ReviewCompositionContinuation.class);
        recovery = mock(ReviewJobRecovery.class);
        terminalWriteOf(job, ReviewJobStatus.COMPLETED);
        var executor = runExecutor(kept, new SandboxResult(0, Map.of(), "done", false, Duration.ofSeconds(1)), true);

        assertThat(executor.executePrepared(job.getId(), job, mock(SandboxSpec.class)))
                .isEqualTo(ReviewJobStatus.COMPLETED);

        verify(recovery, never()).continueComposition(any(), any(), any(), any(), any());
        verify(kept).forget(job);
    }

    private void terminalWriteOf(ReviewJob job, ReviewJobStatus status) {
        when(jobs.lockById(job.getId())).thenReturn(Optional.of(job));
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobs.transitionStatusOwnedBy(eq(job.getId()), eq(status), any(), any(), any(), eq("worker-1")))
                .thenReturn(1);
    }

    private ReviewJobExecutor runExecutor(ReviewCompositionContinuation kept, SandboxResult result, boolean success) {
        var properties = mock(WorkerProperties.class);
        when(properties.resolvedWorkerId()).thenReturn("worker-1");
        worker = Optional.of(properties);
        var parsed = new AgentResult(success, Map.of("rawOutput", OWED_RESULT), RETURNED_USAGE);
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

    private ReviewJobExecutor owedExecutor(ReviewCompositionContinuation kept) {
        var properties = mock(WorkerProperties.class);
        when(properties.resolvedWorkerId()).thenReturn("worker-1");
        worker = Optional.of(properties);
        var owed = owedResult();
        when(sandbox.execute(any())).thenReturn(owed);
        when(practiceAgent.parseResult(owed))
                .thenReturn(new AgentResult(false, Map.of("rawOutput", OWED_RESULT), RETURNED_USAGE));
        var executor = executor(mock(AsyncTaskExecutor.class));
        executor.setCompositionContinuation(kept);
        return executor;
    }

    private ReviewJob owedJob() {
        var job = configuredJob();
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setWorkerId("worker-1");
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
                                null),
                        null,
                        null,
                        600,
                        false,
                        null)
                .toJson(new JsonMapper()));
        return job;
    }

    @Test
    void shouldGiveTheCredentialAndSandboxTheSameDeadlineWhenPreparingAnAttempt() {
        var job = configuredJob();
        job.setAttempt(2);
        var snapshot = ConfigSnapshot.fromJson(job.getConfigSnapshot(), new JsonMapper());
        when(jobs.findByIdQueuedForUpdateSkipLocked(any(), any())).thenReturn(Optional.of(job));
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(policy.permitsReview(job)).thenReturn(true);
        var binding = mock(WorkspaceAgentBinding.class);
        when(binding.getDataHandlingTier()).thenReturn(DataHandlingTier.UNDECLARED);
        when(binding.getMaxConcurrentJobs()).thenReturn(1);
        when(policy.binding(job)).thenReturn(Optional.of(binding));
        when(budgets.decide(job.getWorkspaceId())).thenReturn(LlmBudgetDecision.ALLOWED);
        when(assignments.load(job)).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        when(handler.prepareInputs(job)).thenReturn(new PreparedJobInputs(Map.of(), List.of(), List.of(), null, null));
        when(jwtIssuer.issueForJobUntil(eq(job.getId()), eq(job.getWorkspaceId()), eq(2), any(), any()))
                .thenReturn("issued-job-token");
        when(practiceAgent.buildSandboxSpec(any()))
                .thenReturn(new PracticeSandboxSpec(
                        "practice-runtime", List.of(), Map.of(), Map.of(), "issued-job-token", null));
        when(jobs.updateCapture(any(), anyLong(), any(), anyInt(), any())).thenReturn(1);
        when(jobs.markExecutionStarted(eq(job.getId()), eq(null), any())).thenReturn(1);
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

        assertThat(executor(pool).processJob(job.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);

        var issuedDeadline = ArgumentCaptor.forClass(Instant.class);
        var issuedExpiry = ArgumentCaptor.forClass(Instant.class);
        verify(jwtIssuer)
                .issueForJobUntil(
                        eq(job.getId()),
                        eq(job.getWorkspaceId()),
                        eq(2),
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
        assertThat(spec.resourceLimits().maxRuntime()).isEqualTo(Duration.ofSeconds(snapshot.timeoutSeconds()));
    }

    private static byte[] scriptReport() {
        return SCRIPT_REPORT.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] feedbackReport() {
        return FEEDBACK_REPORT.getBytes(StandardCharsets.UTF_8);
    }

    private static SandboxResult owedResult() {
        return new SandboxResult(
                SandboxLayout.EXIT_COMPOSITION_OWED,
                Map.of(
                        "result.json",
                        OWED_RESULT.getBytes(StandardCharsets.UTF_8),
                        SandboxLayout.PRACTICE_SCRIPT_REPORT_FILE,
                        scriptReport(),
                        SandboxLayout.FEEDBACK_FILENAME,
                        feedbackReport()),
                "owed",
                false,
                Duration.ofSeconds(1));
    }

    @Test
    void shouldRespectTheSandboxPoolSizeWhenClaiming() {
        var pool = new ThreadPoolTaskExecutor();
        pool.setMaxPoolSize(2);
        assertThat(executor(pool).computeCapacity()).isEqualTo(2);
    }

    @Test
    void shouldNotCancelAJobThisWorkerDoesNotOwn() {
        assertThat(executor(mock(AsyncTaskExecutor.class)).cancelLocalJob(UUID.randomUUID(), "cancelled"))
                .isFalse();
        verifyNoInteractions(sandbox);
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
        when(jobs.findByIdQueuedForUpdateSkipLocked(any(), any())).thenReturn(Optional.of(job));
        when(policy.isProcessingSuppressed(job))
                .thenThrow(new JobPreparationException(
                        JobPreparationException.Reason.INPUT_CONTRACT_INVALID, "private SQL/path"));
        assertThat(executor(mock(AsyncTaskExecutor.class)).processJob(job.getId()))
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
        job.setAvailableAt(Instant.now().minusSeconds(1));
        job.setHoldReason(ReviewJob.HOLD_REASON_BUDGET);
        when(jobs.findByIdQueuedForUpdateSkipLocked(any(), any())).thenReturn(Optional.of(job));
        when(policy.permitsReview(job)).thenReturn(true);
        when(budgets.decide(job.getWorkspaceId()))
                .thenReturn(new LlmBudgetDecision(LlmBudgetBlockReason.EXHAUSTED, LlmBudgetBlockReason.NONE));
        assertThat(executor(mock(AsyncTaskExecutor.class)).processJob(job.getId()))
                .isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);
        assertThat(job.getStatus()).isEqualTo(ReviewJobStatus.CANCELLED);
        assertThat(job.getCancellationReason()).isEqualTo(ReviewJobCancellationReason.BUDGET_EXHAUSTED);
        verifyNoInteractions(handler, sandbox);
    }

    @Test
    void shouldMeasureAgeFromSubmissionAfterARepeatedHold() {
        var job = RuntimeReviewTestData.job();
        job.setSubmittedAt(Instant.now().minus(Duration.ofDays(8)));
        job.setAvailableAt(Instant.now().plus(Duration.ofHours(1)));
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
                                null),
                        null,
                        null,
                        600,
                        false,
                        null)
                .toJson(new JsonMapper()));
        var workerCapacity = mock(WorkerCapacityState.class);
        when(workerCapacity.reviewMax()).thenReturn(1);
        capacity = Optional.of(workerCapacity);
        when(jobs.findByIdQueuedForUpdateSkipLocked(any(), any())).thenReturn(Optional.of(job));
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobs.transitionStatus(any(), any(), any(), any(), any())).thenReturn(1);
        when(policy.permitsReview(job)).thenReturn(true);
        var binding = mock(WorkspaceAgentBinding.class);
        when(binding.getDataHandlingTier()).thenReturn(DataHandlingTier.UNDECLARED);
        when(binding.getMaxConcurrentJobs()).thenReturn(1);
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

        assertThat(executor.processJob(job.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);

        assertThat(executor.computeCapacity()).isEqualTo(1);
        verify(workerCapacity).releaseReview();
        assertThat(MDC.get("job.id")).isNull();
        verifyNoInteractions(sandbox);
    }

    @Test
    void shouldCompleteInapplicablePracticesWithoutStartingASandbox() throws Exception {
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
                                null),
                        null,
                        null,
                        600,
                        false,
                        null)
                .toJson(new JsonMapper()));
        var workerCapacity = mock(WorkerCapacityState.class);
        when(workerCapacity.reviewMax()).thenReturn(1);
        capacity = Optional.of(workerCapacity);
        when(jobs.findByIdQueuedForUpdateSkipLocked(any(), any())).thenReturn(Optional.of(job));
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobs.transitionStatus(any(), any(), any(), any(), any())).thenReturn(1);
        when(policy.permitsReview(job)).thenReturn(true);
        var binding = mock(WorkspaceAgentBinding.class);
        when(binding.getDataHandlingTier()).thenReturn(DataHandlingTier.UNDECLARED);
        when(binding.getMaxConcurrentJobs()).thenReturn(1);
        when(policy.binding(job)).thenReturn(Optional.of(binding));
        when(budgets.decide(job.getWorkspaceId())).thenReturn(LlmBudgetDecision.ALLOWED);
        when(assignments.load(job)).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        var now = Instant.now();
        var source = new SourceKind("scm.pull-request.diff");
        var contract = new SourceContractVersion("2.0.0");
        var readiness = new AutomatedReviewReadinessDecision(
                "clear-names",
                now,
                true,
                List.of(),
                List.of(new SourceReadinessCheck(source, contract, now, now, true, List.of())));
        var report = new AutomatedReviewReadinessReport(
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

        assertThat(executor.processJob(job.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);

        assertThat(executor.computeCapacity()).isEqualTo(1);
        assertThat(job.getProcessingStatus()).isEqualTo(ProcessingStatus.PENDING);
        verify(jobs).transitionStatus(eq(job.getId()), eq(ReviewJobStatus.COMPLETED), any(), eq(null), any());
        verify(handler).completeWithoutExecution(job);
        verify(workerCapacity).releaseReview();
        assertThat(MDC.get("job.id")).isNull();
        verifyNoInteractions(sandbox);
    }
}
