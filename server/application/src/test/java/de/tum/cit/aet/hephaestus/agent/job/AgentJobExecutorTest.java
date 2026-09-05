package de.tum.cit.aet.hephaestus.agent.job;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.context.InsufficientEvidenceException;
import de.tum.cit.aet.hephaestus.agent.handler.JobTypeHandlerRegistry;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobTypeHandler;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeAgentRequest;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.NetworkPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCancelledException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SecurityProfile;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetBlockReason;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetDecision;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceManifest;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessDecision;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReport;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceReadinessCheck;
import de.tum.cit.aet.hephaestus.evidence.SourceReadinessReason;
import de.tum.cit.aet.hephaestus.integration.core.signal.PracticeReviewRefusalMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AgentJobExecutorTest extends BaseUnitTest {

    @Mock
    private LlmUsageRecorder usageRecorder;

    @Mock
    private LlmBudgetService llmBudgetService;

    @Mock
    private AgentJobRepository jobRepository;

    @Mock
    private WorkspaceAgentBindingRepository bindingRepository;

    @Mock
    private JobTypeHandlerRegistry handlerRegistry;

    @Mock
    private PracticePiAdapter practiceAgent;

    @Mock
    private WorkerJwtIssuer workerJwtIssuer;

    @Mock
    private SandboxManager sandboxManager;

    @Mock
    private AsyncTaskExecutor sandboxExecutor;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SimpleMeterRegistry meterRegistry;

    private AgentJobExecutor executor;

    private static final de.tum.cit.aet.hephaestus.agent.usage.@org.jspecify.annotations.Nullable LlmAdmissionService
            NO_LIVE_ADMISSION = null;

    private static final AgentProperties AGENT_PROPS = new AgentProperties(
            true, Duration.ofSeconds(1), 5, 5, Duration.ofSeconds(25), Duration.ofDays(14), Duration.ofDays(90));

    private UUID jobId;
    private AgentJob job;
    private WorkspaceAgentBinding binding;
    private ConfigSnapshot snapshot;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();

        executor = new AgentJobExecutor(
                AGENT_PROPS,
                jobRepository,
                bindingRepository,
                handlerRegistry,
                practiceAgent,
                workerJwtIssuer,
                sandboxManager,
                sandboxExecutor,
                transactionTemplate,
                objectMapper,
                meterRegistry,
                new PracticeReviewRefusalMetrics(meterRegistry),
                new AgentJobTelemetry(meterRegistry),
                usageRecorder,
                llmBudgetService,
                NO_LIVE_ADMISSION,
                Optional.empty(),
                Optional.empty());

        jobId = UUID.randomUUID();

        binding = new WorkspaceAgentBinding();
        binding.setId(10L);
        binding.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        binding.setEnabled(true);
        binding.setMaxConcurrentJobs(3);

        snapshot = new ConfigSnapshot(
                ConfigSnapshot.SCHEMA_VERSION,
                "anthropic-messages",
                "https://api.anthropic.com",
                "claude-sonnet-4",
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                600,
                false,
                null);

        job = new AgentJob();
        job.prePersist();
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setConfigSnapshot(snapshot.toJson(objectMapper));
        job.setJobToken("test-token");
        job.setStatus(AgentJobStatus.QUEUED);
        job.setWorkspace(workspaceStub());

        lenient().when(llmBudgetService.decide(anyLong())).thenReturn(LlmBudgetDecision.ALLOWED);
        lenient().when(jobRepository.markExecutionStarted(any(), any(), any())).thenReturn(1);

        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = inv.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        lenient()
                .doAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    java.util.function.Consumer<TransactionStatus> callback = inv.getArgument(0);
                    callback.accept(mock(TransactionStatus.class));
                    return null;
                })
                .when(transactionTemplate)
                .executeWithoutResult(any());

        lenient().when(transactionTemplate.getTransactionManager()).thenReturn(transactionManager);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        lenient()
                .when(workerJwtIssuer.issueForJob(any(), anyLong(), anyInt(), any()))
                .thenReturn("job-jwt");

        lenient()
                .doAnswer(inv -> {
                    Runnable task = inv.getArgument(0);
                    task.run();
                    return null;
                })
                .when(sandboxExecutor)
                .execute(any());
    }

    private static Workspace workspaceStub() {
        Workspace workspace = new Workspace();
        workspace.setId(99L);
        return workspace;
    }

    private AgentJob freshJob() {
        AgentJob freshJob = new AgentJob();
        freshJob.prePersist();
        freshJob.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        freshJob.setWorkspace(workspaceStub());
        freshJob.setConfigSnapshot(job.getConfigSnapshot());
        return freshJob;
    }

    @Nested
    @DisplayName("Job-scoped cancellation (#1138)")
    class ScopedCancellation {

        @Test
        @DisplayName("cancelLocalJob returns false and does not touch the sandbox for a job this worker doesn't run")
        void cancelLocalJobUnknownIsNoOp() {
            boolean cancelled = executor.cancelLocalJob(UUID.randomUUID(), "user-cancel");

            Assertions.assertThat(cancelled).isFalse();
            verify(sandboxManager, never()).cancel(any());
        }

        @Test
        @DisplayName("cancelInFlight on a worker with no local jobs cancels nothing (no DB-wide sweep)")
        void cancelInFlightEmptyIsNoOp() {
            executor.cancelInFlight(AgentJobCancellationReason.DRAIN_GRACEFUL);

            verify(sandboxManager, never()).cancel(any());
            verify(jobRepository, never()).transitionToCancelled(any(), any(), any(), any(), any());
            // Critically: never queries all RUNNING jobs cluster-wide.
            verify(jobRepository, never()).findByStatus(AgentJobStatus.RUNNING);
        }

        @Test
        @DisplayName("does NOT deliver when the fenced terminal write loses ownership (orphan-requeued)")
        void doesNotDeliverWhenFencedOut() {
            // Worker has identity "test-worker" → terminal writes are fenced to the owner.
            executor = new AgentJobExecutor(
                    AGENT_PROPS,
                    jobRepository,
                    bindingRepository,
                    handlerRegistry,
                    practiceAgent,
                    workerJwtIssuer,
                    sandboxManager,
                    sandboxExecutor,
                    transactionTemplate,
                    objectMapper,
                    meterRegistry,
                    new PracticeReviewRefusalMetrics(meterRegistry),
                    new AgentJobTelemetry(meterRegistry),
                    usageRecorder,
                    llmBudgetService,
                    NO_LIVE_ADMISSION,
                    Optional.empty(),
                    Optional.of(workerProps("test-worker")));

            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            JobTypeHandler handler = setupFullExecution();
            // Fence loses: another worker owns the job now (it was orphan-requeued mid-execution).
            when(jobRepository.transitionStatusOwnedBy(any(), any(), any(), any(), any(), any()))
                    .thenReturn(0);

            executor.processJob(jobId);

            // The job is no longer ours — we must not double-deliver the sibling's findings.
            verify(handler, never()).deliver(any());
        }
    }

    @Nested
    @DisplayName("Claim phase")
    class ClaimPhase {

        @Test
        void returnsFalseAndNeverExecutesWhenSkipLockedReturnsEmpty() {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.empty());

            boolean claimed = executor.processJob(jobId);

            assertThat(claimed).isFalse();
            verify(sandboxManager, never()).execute(any());
        }

        @Test
        void returnsFalseAndLeavesJobQueuedWhenConcurrencyLimitReached() {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(3L); // equals max

            boolean claimed = executor.processJob(jobId);

            assertThat(claimed).isFalse();
            verify(jobRepository, never()).save(any());
            verify(sandboxManager, never()).execute(any());
        }

        @Test
        void shouldTransitionToRunningOnSuccessfulClaim() {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            setupFullExecution();

            boolean claimed = executor.processJob(jobId);

            assertThat(claimed).isTrue();
            // Assert the actual claim contract, not just "save was called": status flips to RUNNING.
            ArgumentCaptor<AgentJob> captured = ArgumentCaptor.forClass(AgentJob.class);
            verify(jobRepository).save(captured.capture());
            assertThat(captured.getValue().getStatus()).isEqualTo(AgentJobStatus.RUNNING);
            assertThat(captured.getValue().getStartedAt()).isNotNull();
        }

        @Test
        void modelRefusalKeepsGeneralTelemetryAndAddsPracticeTelemetry() {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            executor.processJob(jobId);

            assertThat(meterRegistry.get("agent.job.model.refused").counter().count())
                    .isOne();
            assertThat(meterRegistry
                            .get("practice.review.refused")
                            .tags("phase", "execution", "reason", "model_unavailable")
                            .counter()
                            .count())
                    .isOne();
        }

        @Test
        void nonPracticeModelRefusalDoesNotCreatePracticeTelemetry() {
            job.setPurpose(AgentPurpose.MENTOR);
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            executor.processJob(jobId);

            assertThat(meterRegistry.get("agent.job.model.refused").counter().count())
                    .isOne();
            assertThat(meterRegistry.find("practice.review.refused").counter()).isNull();
        }
    }

    @Nested
    @DisplayName("Claim-time budget recheck")
    class ClaimTimeBudgetRecheck {

        private LlmBudgetDecision instanceBlocked(LlmBudgetBlockReason reason) {
            return new LlmBudgetDecision(reason, LlmBudgetBlockReason.NONE);
        }

        private void bindFundedBy(FundingSource fundingSource) {
            if (fundingSource == FundingSource.INSTANCE) {
                binding.setInstanceModel(new de.tum.cit.aet.hephaestus.agent.catalog.LlmModel());
            }
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
        }

        @Test
        void holdsAndRequeuesWhenBudgetIsExhaustedAtClaimTime() {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            bindFundedBy(FundingSource.INSTANCE);
            when(llmBudgetService.decide(99L)).thenReturn(instanceBlocked(LlmBudgetBlockReason.EXHAUSTED));
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            boolean claimed = executor.processJob(jobId);

            assertThat(claimed).isFalse();
            verify(sandboxManager, never()).execute(any());

            ArgumentCaptor<AgentJob> saved = ArgumentCaptor.forClass(AgentJob.class);
            verify(jobRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(AgentJobStatus.QUEUED);
            assertThat(saved.getValue().getCancellationReason()).isNull();
            assertThat(saved.getValue().getAvailableAt()).isAfter(Instant.now());
        }

        @Test
        @DisplayName("a budget hold is labelled BUDGET, so raising the cap can release exactly it")
        void marksTheHoldAsABudgetHold() {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            bindFundedBy(FundingSource.INSTANCE);
            when(llmBudgetService.decide(99L)).thenReturn(instanceBlocked(LlmBudgetBlockReason.EXHAUSTED));
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            executor.processJob(jobId);

            ArgumentCaptor<AgentJob> saved = ArgumentCaptor.forClass(AgentJob.class);
            verify(jobRepository).save(saved.capture());
            assertThat(saved.getValue().getHoldReason()).isEqualTo(AgentJob.HOLD_REASON_BUDGET);
        }

        @Test
        @DisplayName("an exhausted INSTANCE cap does not hold a job the workspace funds itself")
        void doesNotHoldWorkspaceFundedWorkWhenOnlyTheInstanceCapIsExhausted() {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            bindFundedBy(FundingSource.WORKSPACE); // no instance model bound = the workspace pays
            when(llmBudgetService.decide(99L)).thenReturn(instanceBlocked(LlmBudgetBlockReason.EXHAUSTED));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            setupFullExecution();

            boolean claimed = executor.processJob(jobId);

            assertThat(claimed).isTrue();
            ArgumentCaptor<AgentJob> saved = ArgumentCaptor.forClass(AgentJob.class);
            verify(jobRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(AgentJobStatus.RUNNING);
        }

        @Test
        @DisplayName("an exhausted BYO cap does hold a job the workspace funds itself")
        void holdsWorkspaceFundedWorkWhenTheByoCapIsExhausted() {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            bindFundedBy(FundingSource.WORKSPACE);
            when(llmBudgetService.decide(99L))
                    .thenReturn(new LlmBudgetDecision(LlmBudgetBlockReason.NONE, LlmBudgetBlockReason.EXHAUSTED));
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            boolean claimed = executor.processJob(jobId);

            assertThat(claimed).isFalse();
            verify(sandboxManager, never()).execute(any());
            ArgumentCaptor<AgentJob> saved = ArgumentCaptor.forClass(AgentJob.class);
            verify(jobRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(AgentJobStatus.QUEUED);
            assertThat(saved.getValue().getHoldReason()).isEqualTo(AgentJob.HOLD_REASON_BUDGET);
        }

        @Test
        @DisplayName("a job whose binding is gone is judged against BOTH caps — never a way around one")
        void aJobWithNoBindingRowIsHeldByEitherCap() {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(llmBudgetService.decide(99L))
                    .thenReturn(new LlmBudgetDecision(LlmBudgetBlockReason.NONE, LlmBudgetBlockReason.EXHAUSTED));
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            boolean claimed = executor.processJob(jobId);

            assertThat(claimed).isFalse();
            ArgumentCaptor<AgentJob> saved = ArgumentCaptor.forClass(AgentJob.class);
            verify(jobRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(AgentJobStatus.QUEUED);
            assertThat(saved.getValue().getHoldReason()).isEqualTo(AgentJob.HOLD_REASON_BUDGET);
        }

        @Test
        void holdsWhenUnpricedUsageBlocksACappedWorkspace() {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            bindFundedBy(FundingSource.INSTANCE);
            when(llmBudgetService.decide(99L)).thenReturn(instanceBlocked(LlmBudgetBlockReason.UNPRICED_USAGE_BLOCKED));
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            boolean claimed = executor.processJob(jobId);

            assertThat(claimed).isFalse();
            verify(sandboxManager, never()).execute(any());

            ArgumentCaptor<AgentJob> saved = ArgumentCaptor.forClass(AgentJob.class);
            verify(jobRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(AgentJobStatus.QUEUED);
            assertThat(saved.getValue().getCancellationReason()).isNull();
            assertThat(saved.getValue().getAvailableAt()).isAfter(Instant.now());
        }

        @Test
        void cancelsAJobHeldPastTheMaxAge() {
            job.setCreatedAt(Instant.now().minus(Duration.ofDays(8)));
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            bindFundedBy(FundingSource.INSTANCE);
            when(llmBudgetService.decide(99L)).thenReturn(instanceBlocked(LlmBudgetBlockReason.EXHAUSTED));
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            boolean claimed = executor.processJob(jobId);

            assertThat(claimed).isFalse();
            verify(sandboxManager, never()).execute(any());

            ArgumentCaptor<AgentJob> saved = ArgumentCaptor.forClass(AgentJob.class);
            verify(jobRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(AgentJobStatus.CANCELLED);
            assertThat(saved.getValue().getCancellationReason()).isEqualTo(AgentJobCancellationReason.BUDGET_EXHAUSTED);
            assertThat(meterRegistry.get("agent.job.budget.refused").counter().count())
                    .isOne();
            assertThat(meterRegistry
                            .get("practice.review.refused")
                            .tags("phase", "execution", "reason", "budget_exhausted")
                            .counter()
                            .count())
                    .isOne();
            // The message must say why it was cancelled and that waiting is over — not merely "expired",
            // which reads like the job itself timed out rather than the budget never being raised.
            assertThat(saved.getValue().getErrorMessage()).contains("budget").contains("7 days old");
        }

        @Test
        void heldPreStartJobNeverWritesAUsageLedgerEntry() {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            bindFundedBy(FundingSource.INSTANCE);
            when(llmBudgetService.decide(99L)).thenReturn(instanceBlocked(LlmBudgetBlockReason.EXHAUSTED));
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            executor.processJob(jobId);

            verify(usageRecorder, never()).record(any(), any());
            verify(usageRecorder, never()).recordUnverifiable(any(), any());
        }

        @Test
        @DisplayName("claiming a previously held job clears its BUDGET hold marker")
        void clearsTheHoldMarkerOnceTheJobIsClaimed() {
            // Otherwise a stale 'BUDGET' marker would survive onto a later crash-retry backoff and let
            // a cap raise fast-forward a job that is backing off for an entirely different reason.
            job.setHoldReason(AgentJob.HOLD_REASON_BUDGET);
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            setupFullExecution();

            assertThat(executor.processJob(jobId)).isTrue();
            ArgumentCaptor<AgentJob> saved = ArgumentCaptor.forClass(AgentJob.class);
            verify(jobRepository).save(saved.capture());
            assertThat(saved.getValue().getHoldReason()).isNull();
        }

        @Test
        void proceedsToConcurrencyGateWhenBudgetIsNotBlocked() {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(llmBudgetService.decide(99L)).thenReturn(LlmBudgetDecision.ALLOWED);
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            setupFullExecution();

            boolean claimed = executor.processJob(jobId);

            assertThat(claimed).isTrue();
            ArgumentCaptor<AgentJob> captured = ArgumentCaptor.forClass(AgentJob.class);
            verify(jobRepository).save(captured.capture());
            assertThat(captured.getValue().getStatus()).isEqualTo(AgentJobStatus.RUNNING);
        }
    }

    @Nested
    class ProvenanceDigests {

        @Test
        void areStampedBeforeTheSandboxRuns_soAFailedRunKeepsThem() {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            setupFullExecution();

            executor.processJob(jobId);

            // The adapter's prompt digest, and an inputs digest over the merged file set — written first, so an
            // observation can always be tied to what produced it even when the sandbox then fails.
            ArgumentCaptor<AgentJobRepository.ProvenanceStamp> stamp =
                    ArgumentCaptor.forClass(AgentJobRepository.ProvenanceStamp.class);
            InOrder order = inOrder(jobRepository, sandboxManager);
            order.verify(jobRepository).updateProvenanceDigests(eq(jobId), isNull(), eq(0), stamp.capture());
            order.verify(sandboxManager).execute(any());
            assertThat(stamp.getValue().promptDigest()).isEqualTo("prompt-digest");
            assertThat(stamp.getValue().inputsDigest()).matches("[0-9a-f]{64}");
        }

        @Test
        void aWriteMatchingNoJobRow_failsTheRunRatherThanBurningTheLlmBudget() {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            JobTypeHandler handler = mock(JobTypeHandler.class);
            when(handlerRegistry.getHandler(AgentJobType.PULL_REQUEST_REVIEW)).thenReturn(handler);
            when(handler.prepareInputs(any()))
                    .thenReturn(PreparedJobInputs.filesOnly(Map.of("task.json", "{}".getBytes())));
            when(practiceAgent.buildSandboxSpec(any())).thenReturn(minimalSpec());
            when(jobRepository.updateProvenanceDigests(any(), any(), anyInt(), any()))
                    .thenReturn(0);
            when(jobRepository.transitionStatus(any(), eq(AgentJobStatus.FAILED), any(), any(), any()))
                    .thenReturn(1);

            executor.processJob(jobId);

            verify(sandboxManager, never()).execute(any());
            verify(usageRecorder, never()).recordUnverifiable(any(), any());
            verify(usageRecorder, never()).record(any(), any());
        }

        @Test
        void insufficientEvidencePersistsTypedReadinessWithoutStartingTheSandbox() {
            stubClaimableJob();
            JobTypeHandler handler = mock(JobTypeHandler.class);
            when(handlerRegistry.getHandler(AgentJobType.PULL_REQUEST_REVIEW)).thenReturn(handler);
            Instant now = Instant.parse("2026-08-03T10:00:00Z");
            SourceContractVersion version = new SourceContractVersion("1.0.0");
            String artifactKind = "scm.pull_request";
            SourceKind source = new SourceKind("scm.pull-request.diff");
            ArtifactSourceManifest manifest = new ArtifactSourceManifest(
                    version,
                    "a".repeat(64),
                    artifactKind,
                    now,
                    List.of(new SourceCapture(
                            source, new SourceCaptureState.NotCollected(SourceAbsenceReason.DISABLED), List.of())));
            SourceReadinessCheck assessment = new SourceReadinessCheck(
                    source, version, now, now, false, List.of(SourceReadinessReason.SOURCE_NOT_AVAILABLE));
            AutomatedReviewReadinessReport readiness = new AutomatedReviewReadinessReport(
                    version,
                    "a".repeat(64),
                    artifactKind,
                    now,
                    now,
                    List.of(new AutomatedReviewReadinessDecision(
                            "example", now, false, List.of(), List.of(assessment))));
            PreparedJobInputs inputs =
                    new PreparedJobInputs(Map.of(SandboxLayout.MANIFEST_PATH, "{}".getBytes()), manifest, readiness);
            when(handler.prepareInputs(any()))
                    .thenThrow(new InsufficientEvidenceException("No practice has sufficient evidence", inputs));
            when(jobRepository.updateProvenanceDigests(any(), any(), anyInt(), any()))
                    .thenReturn(1);
            when(jobRepository.transitionToEvidenceRefused(any(), any(), anyInt(), any(), any()))
                    .thenReturn(1);

            executor.processJob(jobId);

            ArgumentCaptor<AgentJobRepository.ProvenanceStamp> stamp =
                    ArgumentCaptor.forClass(AgentJobRepository.ProvenanceStamp.class);
            verify(jobRepository).updateProvenanceDigests(eq(jobId), isNull(), eq(0), stamp.capture());
            var provenance = stamp.getValue();
            org.junit.jupiter.api.Assertions.assertNotNull(provenance);
            var storedReadiness = provenance.reviewReadiness();
            org.junit.jupiter.api.Assertions.assertNotNull(storedReadiness);
            assertThat(storedReadiness.path("decisions").get(0).path("ready").asBoolean())
                    .isFalse();
            ArgumentCaptor<JsonNode> output = ArgumentCaptor.forClass(JsonNode.class);
            verify(jobRepository).transitionToEvidenceRefused(eq(jobId), isNull(), eq(0), any(), output.capture());
            assertThat(output.getValue().path("outcome").asString()).isEqualTo("INSUFFICIENT_EVIDENCE");
            assertThat(meterRegistry
                            .get("practice.review.refused")
                            .tags("phase", "execution", "reason", "insufficient_evidence")
                            .counter()
                            .count())
                    .isOne();
            // The pre-existing execution-duration label is unchanged; the lifecycle contract records
            // the committed terminal state as completed.
            assertThat(meterRegistry
                            .get("agent.job.execution.duration")
                            .tag("status", "INSUFFICIENT_EVIDENCE")
                            .timer()
                            .count())
                    .isOne();
            assertThat(meterRegistry
                            .get("agent.job.total")
                            .tag("outcome", "completed")
                            .counter()
                            .count())
                    .isOne();
            verify(sandboxManager, never()).execute(any());
        }

        @ParameterizedTest
        @ValueSource(strings = {"Pinned review head is unavailable", "Review diff is empty or unavailable"})
        void contextPreparationFailureBeforeSandboxDoesNotCreateUnpricedUsage(String failureMessage) {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.transitionStatus(any(), eq(AgentJobStatus.FAILED), any(), any(), any()))
                    .thenReturn(1);

            JobTypeHandler handler = mock(JobTypeHandler.class);
            when(handlerRegistry.getHandler(AgentJobType.PULL_REQUEST_REVIEW)).thenReturn(handler);
            when(handler.prepareInputs(any())).thenThrow(new IllegalStateException(failureMessage));

            executor.processJob(jobId);

            verify(sandboxManager, never()).execute(any());
            verify(usageRecorder, never()).recordUnverifiable(any(), any());
            verify(usageRecorder, never()).record(any(), any());
        }
    }

    @Nested
    class FullExecution {

        @Test
        void shouldCompleteJobSuccessfully() {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            setupFullExecution();

            AgentJob freshJob = freshJob();
            when(jobRepository.findById(any(UUID.class))).thenReturn(Optional.of(freshJob));
            when(jobRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.transitionStatus(any(), eq(AgentJobStatus.COMPLETED), any(), any(), any()))
                    .thenReturn(1);

            boolean claimed = executor.processJob(jobId);

            assertThat(claimed).isTrue();
            verify(sandboxManager).execute(any());
            verify(jobRepository)
                    .transitionStatus(
                            any(), eq(AgentJobStatus.COMPLETED), any(), any(), eq(Set.of(AgentJobStatus.RUNNING)));
        }

        @Test
        void shouldMarkFailedWithAnErrorMessageNamingTheExitCode() {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            SandboxResult failResult = new SandboxResult(1, Map.of(), "error output", false, Duration.ofMinutes(2));
            setupFullExecution(failResult);

            AgentJob freshJob = freshJob();
            when(jobRepository.findById(any(UUID.class))).thenReturn(Optional.of(freshJob));
            when(jobRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.transitionStatus(any(), any(), any(), any(), any()))
                    .thenReturn(1);

            executor.processJob(jobId);

            verify(jobRepository)
                    .transitionStatus(
                            eq(jobId),
                            eq(AgentJobStatus.FAILED),
                            any(),
                            eq("Container exited with code 1"),
                            eq(Set.of(AgentJobStatus.RUNNING)));
        }

        @Test
        void emitsEnvelopeMismatchOnExit42() {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            SandboxResult envelopeMismatch =
                    new SandboxResult(42, Map.of(), "envelope drift", false, Duration.ofSeconds(5));
            setupFullExecution(envelopeMismatch);

            AgentJob freshJob = freshJob();
            when(jobRepository.findById(any(UUID.class))).thenReturn(Optional.of(freshJob));
            when(jobRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.transitionStatus(any(), any(), any(), any(), any()))
                    .thenReturn(1);

            executor.processJob(jobId);

            assertThat(meterRegistry.counter("agent.pi.envelope.mismatch").count())
                    .isEqualTo(1d);
            verify(jobRepository)
                    .transitionStatus(
                            eq(jobId),
                            eq(AgentJobStatus.FAILED),
                            any(),
                            eq("Container exited with code 42"),
                            eq(Set.of(AgentJobStatus.RUNNING)));
        }

        @Test
        void shouldMarkTimedOutOnTimeout() {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            SandboxResult timeoutResult = new SandboxResult(137, Map.of(), "timed out", true, Duration.ofMinutes(10));
            setupFullExecution(timeoutResult);

            AgentJob freshJob = freshJob();
            when(jobRepository.findById(any(UUID.class))).thenReturn(Optional.of(freshJob));
            when(jobRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.transitionStatus(any(), any(), any(), any(), any()))
                    .thenReturn(1);

            executor.processJob(jobId);

            verify(jobRepository)
                    .transitionStatus(
                            eq(jobId),
                            eq(AgentJobStatus.TIMED_OUT),
                            any(),
                            eq("Container timed out"),
                            eq(Set.of(AgentJobStatus.RUNNING)));
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void shouldTransitionToCancelledOnCancellation() {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            setupFullExecutionWithException(new SandboxCancelledException("cancelled"));

            executor.processJob(jobId);

            verify(jobRepository)
                    .transitionStatus(
                            eq(jobId),
                            eq(AgentJobStatus.CANCELLED),
                            any(),
                            eq("Cancelled during execution"),
                            eq(Set.of(AgentJobStatus.RUNNING)));
        }

        @Test
        void shouldMarkFailedCarryingTheThrownMessageOntoTheRow() {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            setupFullExecutionWithException(new RuntimeException("Docker daemon unreachable"));

            executor.processJob(jobId);

            verify(jobRepository)
                    .transitionStatus(
                            eq(jobId),
                            eq(AgentJobStatus.FAILED),
                            any(),
                            eq("Docker daemon unreachable"),
                            eq(Set.of(AgentJobStatus.RUNNING)));
        }
    }

    @Nested
    @DisplayName("Error classification")
    class ErrorClassification {

        static Stream<Arguments> failures() {
            return Stream.of(
                    Arguments.of(
                            new SandboxInfrastructureException("docker daemon unreachable"),
                            true,
                            "provably-transient infrastructure"),
                    Arguments.of(
                            new java.io.IOException("connection reset"), true, "a bare IOException is network-ish"),
                    Arguments.of(
                            new SandboxException("path traversal detected"),
                            false,
                            "a plain SandboxException is validation/config/unexpected, deterministic across retries"),
                    Arguments.of(
                            new SandboxCancelledException("cancelled"),
                            false,
                            "cancellation is a SandboxException subtype, but handled separately"),
                    Arguments.of(new RuntimeException("parse error"), false, "unclassified defaults to not-retryable"));
        }

        @ParameterizedTest(name = "{2}")
        @MethodSource("failures")
        void classifiesRetryableInfraFailures(Exception failure, boolean expected, String why) {
            assertThat(AgentJobExecutor.isRetryableInfraFailure(failure))
                    .as(why)
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName(
                "a classified infra failure is requeued (not failed) with backoff + a rotated token, fenced to this worker")
        void infraFailureIsRequeuedNotFailed() {
            executor = new AgentJobExecutor(
                    AGENT_PROPS,
                    jobRepository,
                    bindingRepository,
                    handlerRegistry,
                    practiceAgent,
                    workerJwtIssuer,
                    sandboxManager,
                    sandboxExecutor,
                    transactionTemplate,
                    objectMapper,
                    meterRegistry,
                    new PracticeReviewRefusalMetrics(meterRegistry),
                    new AgentJobTelemetry(meterRegistry),
                    usageRecorder,
                    llmBudgetService,
                    NO_LIVE_ADMISSION,
                    Optional.empty(),
                    Optional.of(workerProps("infra-retry-worker")));
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.requeueOrphan(
                            eq(jobId), eq("infra-retry-worker"), eq(AGENT_PROPS.maxRetries()), any(), any(), any()))
                    .thenReturn(1);

            setupFullExecutionWithException(
                    new de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException(
                            "image pull failed"));

            executor.processJob(jobId);

            var availableAt = ArgumentCaptor.forClass(Instant.class);
            var newToken = ArgumentCaptor.forClass(String.class);
            var newTokenHash = ArgumentCaptor.forClass(String.class);
            verify(jobRepository)
                    .requeueOrphan(
                            eq(jobId),
                            eq("infra-retry-worker"),
                            eq(AGENT_PROPS.maxRetries()),
                            availableAt.capture(),
                            newToken.capture(),
                            newTokenHash.capture());
            // Backoff: the retry must not be immediately eligible again, or an infra blip becomes a
            // hot loop. Asserted as a range, since the exact instant is clock-dependent.
            assertThat(availableAt.getValue()).isAfter(Instant.now());
            // Rotation: the retry must NOT reuse the token the failed attempt handed the sandbox —
            // that token may still be held by a stuck container.
            assertThat(newToken.getValue()).isNotEqualTo("test-token").isNotBlank();
            assertThat(newTokenHash.getValue()).isEqualTo(AgentJob.computeTokenHash(newToken.getValue()));
            verify(jobRepository, never()).transitionStatus(any(), eq(AgentJobStatus.FAILED), any(), any(), any());
            verify(jobRepository, never())
                    .transitionStatusOwnedBy(any(), eq(AgentJobStatus.FAILED), any(), any(), any(), any());
        }

        @Test
        @DisplayName(
                "a classified infra failure falls through to FAILED when the requeue CAS loses (retry cap exhausted)")
        void infraFailureFallsThroughToFailedWhenRequeueLoses() {
            executor = new AgentJobExecutor(
                    AGENT_PROPS,
                    jobRepository,
                    bindingRepository,
                    handlerRegistry,
                    practiceAgent,
                    workerJwtIssuer,
                    sandboxManager,
                    sandboxExecutor,
                    transactionTemplate,
                    objectMapper,
                    meterRegistry,
                    new PracticeReviewRefusalMetrics(meterRegistry),
                    new AgentJobTelemetry(meterRegistry),
                    usageRecorder,
                    llmBudgetService,
                    NO_LIVE_ADMISSION,
                    Optional.empty(),
                    Optional.of(workerProps("infra-retry-worker-2")));
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.requeueOrphan(any(), any(), anyInt(), any(), any(), any()))
                    .thenReturn(0);
            when(jobRepository.transitionStatusOwnedBy(any(), eq(AgentJobStatus.FAILED), any(), any(), any(), any()))
                    .thenReturn(1);

            setupFullExecutionWithException(
                    new de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException(
                            "image pull failed"));

            executor.processJob(jobId);

            verify(jobRepository)
                    .transitionStatusOwnedBy(
                            any(), eq(AgentJobStatus.FAILED), any(), any(), any(), eq("infra-retry-worker-2"));
        }

        @Test
        @DisplayName("an unclassified exception still fails immediately, without attempting a requeue")
        void unclassifiedExceptionNeverAttemptsRequeue() {
            executor = new AgentJobExecutor(
                    AGENT_PROPS,
                    jobRepository,
                    bindingRepository,
                    handlerRegistry,
                    practiceAgent,
                    workerJwtIssuer,
                    sandboxManager,
                    sandboxExecutor,
                    transactionTemplate,
                    objectMapper,
                    meterRegistry,
                    new PracticeReviewRefusalMetrics(meterRegistry),
                    new AgentJobTelemetry(meterRegistry),
                    usageRecorder,
                    llmBudgetService,
                    NO_LIVE_ADMISSION,
                    Optional.empty(),
                    Optional.of(workerProps("infra-retry-worker-3")));
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.transitionStatusOwnedBy(any(), eq(AgentJobStatus.FAILED), any(), any(), any(), any()))
                    .thenReturn(1);

            setupFullExecutionWithException(new IllegalStateException("unrecognised failure"));

            executor.processJob(jobId);

            verify(jobRepository, never()).requeueOrphan(any(), any(), anyInt(), any(), any(), any());
            verify(jobRepository)
                    .transitionStatusOwnedBy(
                            any(), eq(AgentJobStatus.FAILED), any(), any(), any(), eq("infra-retry-worker-3"));
        }
    }

    @Nested
    @DisplayName("Unpriced usage ledger fallback")
    class UnpricedUsageLedgerFallback {

        @Test
        void cancelledAfterStart_recordsAnUnpricedLedgerEntry() {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.transitionStatus(any(), eq(AgentJobStatus.CANCELLED), any(), any(), any()))
                    .thenReturn(1);
            when(jobRepository.findByIdWithWorkspace(jobId)).thenReturn(Optional.of(job));

            setupFullExecutionWithException(new SandboxCancelledException("cancelled"));

            executor.processJob(jobId);

            ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                    ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(usageRecorder).recordUnverifiable(eq(99L), sample.capture());
            // job (the claimed entity) has its own id from prePersist() — distinct from the poll's
            // jobId in this fixture; the ledger sourceId must be the entity's real id.
            assertThat(sample.getValue().sourceId()).isEqualTo(job.getId());
            assertThat(sample.getValue().model()).isEqualTo("claude-sonnet-4");
            assertThat(sample.getValue().inputTokens()).isZero();
            assertThat(sample.getValue().totalCalls()).isZero();
            verify(usageRecorder, never()).record(any(), any());
        }

        @Test
        @DisplayName("a crashed job that made priced proxy calls bills them PRICED, not zero")
        void cancelledAfterStart_withProxyMeteredCalls_recordsPricedLedgerEntry() {
            var priced = new de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot(
                    de.tum.cit.aet.hephaestus.agent.usage.FundingSource.INSTANCE,
                    de.tum.cit.aet.hephaestus.agent.usage.PricingState.PRICED,
                    1L,
                    null,
                    new java.math.BigDecimal("1.00"),
                    new java.math.BigDecimal("2.00"),
                    new java.math.BigDecimal("0.10"),
                    new java.math.BigDecimal("0.20"));
            job.setConfigSnapshot(snapshot.withPriceSnapshot(priced).toJson(objectMapper));

            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.transitionStatus(any(), eq(AgentJobStatus.CANCELLED), any(), any(), any()))
                    .thenReturn(1);
            when(jobRepository.findByIdWithWorkspace(jobId)).thenReturn(Optional.of(job));
            when(jobRepository.findLlmUsageById(job.getId()))
                    .thenReturn(Optional.of(new AgentJobLlmUsage(3, 800, 500, 40, 200, 0)));

            setupFullExecutionWithException(new SandboxCancelledException("cancelled"));

            executor.processJob(jobId);

            ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                    ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(usageRecorder).record(eq(99L), sample.capture());
            assertThat(sample.getValue().totalCalls()).isEqualTo(3);
            assertThat(sample.getValue().inputTokens()).isEqualTo(800);
            assertThat(sample.getValue().outputTokens()).isEqualTo(500);
            verify(usageRecorder, never()).recordUnverifiable(any(), any());
        }

        @Test
        @DisplayName(
                "a cancellation fence loss does not write usage outside the transaction that won the state transition")
        void cancelledAfterStart_fenceLost_doesNotRecordOutsideWinningTransaction() {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.transitionStatus(any(), eq(AgentJobStatus.CANCELLED), any(), any(), any()))
                    .thenReturn(0);
            when(jobRepository.findByIdWithWorkspace(jobId)).thenReturn(Optional.of(job));

            setupFullExecutionWithException(new SandboxCancelledException("cancelled"));

            executor.processJob(jobId);

            verify(usageRecorder, never()).recordUnverifiable(any(), any());
            verify(usageRecorder, never()).record(any(), any());
        }

        @Test
        @DisplayName(
                "worker-drain records an attempt-aware UNPRICED ledger entry in the same transaction as its winning CAS")
        void workerDrain_cancelInFlight_recordsAnUnpricedLedgerEntry() throws Exception {
            job.setExecutionStartedAt(Instant.now());
            java.lang.reflect.Field localRunningJobsField = AgentJobExecutor.class.getDeclaredField("localRunningJobs");
            localRunningJobsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<UUID> localRunningJobs = (Set<UUID>) localRunningJobsField.get(executor);
            localRunningJobs.add(jobId);

            when(jobRepository.findByIdWithWorkspaceForUpdate(jobId)).thenReturn(Optional.of(job));
            when(jobRepository.transitionToCancelled(
                            eq(jobId),
                            any(),
                            eq("worker draining"),
                            eq(AgentJobCancellationReason.DRAIN_GRACEFUL),
                            eq(Set.of(AgentJobStatus.RUNNING))))
                    .thenReturn(1);

            executor.cancelInFlight(AgentJobCancellationReason.DRAIN_GRACEFUL);

            verify(sandboxManager).cancel(jobId);
            ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                    ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(usageRecorder).recordUnverifiable(eq(99L), sample.capture());
            assertThat(sample.getValue().sourceId()).isEqualTo(job.getId());
            assertThat(sample.getValue().sourceAttempt()).isEqualTo(job.getRetryCount());
            verify(usageRecorder, never()).record(any(), any());
        }

        @Test
        void workerDrain_whileJobIsStillPreparing_neverTouchesTheLedger() throws Exception {
            java.lang.reflect.Field localRunningJobsField = AgentJobExecutor.class.getDeclaredField("localRunningJobs");
            localRunningJobsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<UUID> localRunningJobs = (Set<UUID>) localRunningJobsField.get(executor);
            localRunningJobs.add(jobId);

            when(jobRepository.findByIdWithWorkspaceForUpdate(jobId)).thenReturn(Optional.of(job));
            when(jobRepository.transitionToCancelled(
                            eq(jobId),
                            any(),
                            eq("worker draining"),
                            eq(AgentJobCancellationReason.DRAIN_GRACEFUL),
                            eq(Set.of(AgentJobStatus.RUNNING))))
                    .thenReturn(1);

            executor.cancelInFlight(AgentJobCancellationReason.DRAIN_GRACEFUL);

            verify(sandboxManager).cancel(jobId);
            verify(usageRecorder, never()).recordUnverifiable(any(), any());
            verify(usageRecorder, never()).record(any(), any());
        }

        @Test
        void missingOrMalformedUsageJson_recordsAnUnpricedLedgerEntryOnNormalCompletion() {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // setupFullExecution's AgentResult carries no usage — the Pi runner's usage.json was
            // missing/malformed. The sandbox itself still exits 0 (COMPLETED), unlike a hard failure.
            setupFullExecution();

            AgentJob freshJob = freshJob();
            when(jobRepository.findById(any(UUID.class))).thenReturn(Optional.of(freshJob));
            when(jobRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.transitionStatus(any(), eq(AgentJobStatus.COMPLETED), any(), any(), any()))
                    .thenReturn(1);

            executor.processJob(jobId);

            ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                    ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(usageRecorder).recordUnverifiable(eq(99L), sample.capture());
            assertThat(sample.getValue().sourceId()).isEqualTo(freshJob.getId());
            assertThat(sample.getValue().model()).isEqualTo("claude-sonnet-4");
            assertThat(sample.getValue().totalCalls()).isZero();
            verify(usageRecorder, never()).record(any(), any());
        }

        @Test
        void reportedCallWithZeroTokens_recordsAnUnpricedLedgerEntryThatKeepsTheCall() {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            setupFullExecution();
            when(practiceAgent.parseResult(any()))
                    .thenReturn(new AgentResult(
                            true,
                            Map.of("review", "LGTM"),
                            new AgentResult.LlmUsage("claude-sonnet-4", 0, 0, 0, 0, 0, 0.0, 1)));

            AgentJob freshJob = freshJob();
            when(jobRepository.findById(any(UUID.class))).thenReturn(Optional.of(freshJob));
            when(jobRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.transitionStatus(any(), eq(AgentJobStatus.COMPLETED), any(), any(), any()))
                    .thenReturn(1);

            executor.processJob(jobId);

            ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                    ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(usageRecorder).recordUnverifiable(eq(99L), sample.capture());
            assertThat(sample.getValue().sourceId()).isEqualTo(freshJob.getId());
            assertThat(sample.getValue().model()).isEqualTo("claude-sonnet-4");
            assertThat(sample.getValue().inputTokens()).isZero();
            assertThat(sample.getValue().outputTokens()).isZero();
            assertThat(sample.getValue().totalCalls()).isEqualTo(1);
            verify(usageRecorder, never()).record(any(), any());
        }

        @Test
        @DisplayName("a clean finish with no runner usage still bills the calls the proxy watched go out")
        void cleanCompletionWithoutRunnerUsage_billsTheProxyAccumulators() {
            job.setConfigSnapshot(snapshot.withPriceSnapshot(pricedSnapshot()).toJson(objectMapper));
            stubClaimableJob();
            setupFullExecution(); // AgentResult with no usage at all

            AgentJob freshJob = freshJob();
            when(jobRepository.findById(any(UUID.class))).thenReturn(Optional.of(freshJob));
            when(jobRepository.findLlmUsageById(jobId))
                    .thenReturn(Optional.of(new AgentJobLlmUsage(4, 900, 600, 30, 100, 0)));
            when(jobRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.transitionStatus(any(), eq(AgentJobStatus.COMPLETED), any(), any(), any()))
                    .thenReturn(1);

            executor.processJob(jobId);

            ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                    ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(usageRecorder).record(eq(99L), sample.capture());
            assertThat(sample.getValue().totalCalls()).isEqualTo(4);
            assertThat(sample.getValue().inputTokens()).isEqualTo(900);
            assertThat(sample.getValue().outputTokens()).isEqualTo(600);
            assertThat(sample.getValue().cacheReadTokens()).isEqualTo(100);
            assertThat(sample.getValue().price().pricingState())
                    .isEqualTo(de.tum.cit.aet.hephaestus.agent.usage.PricingState.PRICED);
            verify(usageRecorder, never()).recordUnverifiable(any(), any());
        }

        @Test
        @DisplayName("a runner that did report usage wins over the proxy accumulators — never both")
        void cleanCompletionWithRunnerUsage_prefersTheRunnerReport() {
            // The runner's report also covers streamed calls the proxy accumulator skips, so it is the
            // better number when it exists. Falling back must never turn into adding the two together.
            job.setConfigSnapshot(snapshot.withPriceSnapshot(pricedSnapshot()).toJson(objectMapper));
            stubClaimableJob();
            setupFullExecution();
            when(practiceAgent.parseResult(any()))
                    .thenReturn(new AgentResult(
                            true,
                            Map.of("review", "LGTM"),
                            new AgentResult.LlmUsage("claude-sonnet-4", 1000, 700, 50, 10, 20, 0.0, 6)));

            AgentJob freshJob = freshJob();
            when(jobRepository.findById(any(UUID.class))).thenReturn(Optional.of(freshJob));
            lenient()
                    .when(jobRepository.findLlmUsageById(jobId))
                    .thenReturn(Optional.of(new AgentJobLlmUsage(4, 900, 600, 30, 100, 0)));
            when(jobRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.transitionStatus(any(), eq(AgentJobStatus.COMPLETED), any(), any(), any()))
                    .thenReturn(1);

            executor.processJob(jobId);

            ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                    ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(usageRecorder).record(eq(99L), sample.capture());
            assertThat(sample.getValue().totalCalls()).isEqualTo(6);
            assertThat(sample.getValue().inputTokens()).isEqualTo(1000);
            assertThat(sample.getValue().outputTokens()).isEqualTo(700);
        }
    }

    @Nested
    @DisplayName("terminal-write retry — only failures a retry can resolve")
    class TerminalWriteRetry {

        @Test
        @DisplayName("a deterministic failure is attempted once, not three times")
        void deterministicFailureIsNotRetried() {
            stubClaimableJob();
            setupFullExecution();
            when(jobRepository.transitionStatus(any(), eq(AgentJobStatus.COMPLETED), any(), any(), any()))
                    .thenReturn(1);
            when(jobRepository.findById(any(UUID.class)))
                    .thenThrow(new IllegalStateException("Started job has no admitted LLM price snapshot"));

            executor.processJob(jobId);

            // Each attempt opens with the fenced terminal transition, so counting it counts attempts.
            verify(jobRepository, times(1)).transitionStatus(any(), eq(AgentJobStatus.COMPLETED), any(), any(), any());
            verify(usageRecorder, never()).record(any(), any());
            verify(usageRecorder, never()).recordUnverifiable(any(), any());
        }

        @Test
        @DisplayName("a transient data-access failure is retried and the terminal write still lands")
        void transientFailureIsRetriedUntilItSucceeds() {
            job.setConfigSnapshot(snapshot.withPriceSnapshot(pricedSnapshot()).toJson(objectMapper));
            stubClaimableJob();
            setupFullExecution();
            when(jobRepository.transitionStatus(any(), eq(AgentJobStatus.COMPLETED), any(), any(), any()))
                    .thenReturn(1);
            when(jobRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.findLlmUsageById(jobId))
                    .thenReturn(Optional.of(new AgentJobLlmUsage(2, 100, 50, 0, 0, 0)));
            AgentJob freshJob = freshJob();
            AtomicInteger loads = new AtomicInteger();
            when(jobRepository.findById(any(UUID.class))).thenAnswer(inv -> {
                if (loads.incrementAndGet() == 1) {
                    throw new TransientDataAccessResourceException("connection reset");
                }
                return Optional.of(freshJob);
            });

            executor.processJob(jobId);

            verify(jobRepository, times(2)).transitionStatus(any(), eq(AgentJobStatus.COMPLETED), any(), any(), any());

            ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                    ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(usageRecorder, times(1)).record(eq(99L), sample.capture());
            assertThat(sample.getValue().inputTokens()).isEqualTo(100L);
            assertThat(sample.getValue().outputTokens()).isEqualTo(50L);
            assertThat(sample.getValue().totalCalls()).isEqualTo(2);
        }

        @Test
        @DisplayName("classification covers pool exhaustion and wrapped causes, and stops at deterministic errors")
        void classifierRecognisesOnlyResolvableInfrastructureFailures() {
            assertThat(AgentJobExecutor.TERMINAL_PERSIST_POLICY.shouldRetry(
                            new TransientDataAccessResourceException("blip")))
                    .isTrue();
            assertThat(AgentJobExecutor.TERMINAL_PERSIST_POLICY.shouldRetry(
                            new org.springframework.transaction.CannotCreateTransactionException("pool exhausted")))
                    .isTrue();
            // A TransactionTemplate and JPA both surface the underlying failure wrapped, so the match has
            // to reach the cause and not just the top of the chain.
            assertThat(AgentJobExecutor.TERMINAL_PERSIST_POLICY.shouldRetry(new RuntimeException(
                            "wrapped", new org.springframework.dao.RecoverableDataAccessException("gone"))))
                    .isTrue();
            assertThat(AgentJobExecutor.TERMINAL_PERSIST_POLICY.shouldRetry(
                            new IllegalStateException("no price snapshot")))
                    .isFalse();
            assertThat(AgentJobExecutor.TERMINAL_PERSIST_POLICY.shouldRetry(
                            new org.springframework.dao.DataIntegrityViolationException("constraint")))
                    .isFalse();
        }

        @Test
        @DisplayName("a transient failure on every attempt gives up as PERSISTENCE_FAILED, not as a job failure")
        void exhaustedRetriesReachTheCallerAsATerminalPersistenceFailure() {
            // The give-up has to stay distinguishable from an ordinary job failure: the provider work is
            // already done and paid for, so the row must be left RUNNING for the zombie sweeper rather
            // than transitioned to FAILED (which would also hand the job back for a fresh, second-charging
            // run). That distinction is what the exception translation around the retries carries.
            stubClaimableJob();
            setupFullExecution();
            when(jobRepository.transitionStatus(any(), eq(AgentJobStatus.COMPLETED), any(), any(), any()))
                    .thenReturn(1);
            when(jobRepository.findById(any(UUID.class)))
                    .thenThrow(new TransientDataAccessResourceException("connection reset"));

            executor.processJob(jobId);

            // Three attempts (the policy allows two retries), then give up.
            verify(jobRepository, times(3)).transitionStatus(any(), eq(AgentJobStatus.COMPLETED), any(), any(), any());
            verify(jobRepository, never()).transitionStatus(any(), eq(AgentJobStatus.FAILED), any(), any(), any());
            verify(usageRecorder, never()).record(any(), any());
            var timer = meterRegistry
                    .find("agent.job.execution.duration")
                    .tag("status", "PERSISTENCE_FAILED")
                    .timer();
            org.junit.jupiter.api.Assertions.assertNotNull(timer);
            assertThat(timer.count()).isEqualTo(1L);
        }
    }

    @Nested
    class LlmProxyRouting {

        @Test
        @DisplayName("the practice request carries resolved routing and an attempt-scoped job JWT")
        void passesResolvedRoutingAndJobJwtToSandboxRequest() {
            executor = new AgentJobExecutor(
                    AGENT_PROPS,
                    jobRepository,
                    bindingRepository,
                    handlerRegistry,
                    practiceAgent,
                    workerJwtIssuer,
                    sandboxManager,
                    sandboxExecutor,
                    transactionTemplate,
                    objectMapper,
                    meterRegistry,
                    new PracticeReviewRefusalMetrics(meterRegistry),
                    new AgentJobTelemetry(meterRegistry),
                    usageRecorder,
                    llmBudgetService,
                    NO_LIVE_ADMISSION,
                    Optional.empty(),
                    Optional.of(workerProps("test-worker")));

            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            setupFullExecution();

            AgentJob freshJob = freshJob();
            when(jobRepository.findById(any(UUID.class))).thenReturn(Optional.of(freshJob));
            when(jobRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.transitionStatusOwnedBy(any(), any(), any(), any(), any(), any()))
                    .thenReturn(1);

            executor.processJob(jobId);

            ArgumentCaptor<PracticeAgentRequest> captor = ArgumentCaptor.forClass(PracticeAgentRequest.class);
            verify(practiceAgent).buildSandboxSpec(captor.capture());
            PracticeAgentRequest request = captor.getValue();

            assertThat(request.apiProtocol()).isEqualTo("anthropic-messages");
            assertThat(request.upstreamModelId()).isEqualTo("claude-sonnet-4");
            assertThat(request.jobToken()).isEqualTo("job-jwt");
        }
    }

    @Nested
    @DisplayName("Poll loop capacity math")
    class PollLoopCapacity {

        /**
         * The jobs this worker holds are the claim bound, and the only seam that fills the set is a
         * committed claim — more machinery than a capacity assertion needs.
         */
        private Set<UUID> heldJobs() throws Exception {
            java.lang.reflect.Field field = AgentJobExecutor.class.getDeclaredField("localRunningJobs");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<UUID> held = (Set<UUID>) requireNonNull(field.get(executor));
            return held;
        }

        @Test
        @DisplayName("no WorkerCapacityState (worker role config absent) falls back to claimBatchSize")
        void noCapacityStateFallsBackToClaimBatchSize() {
            assertThat(executor.computeCapacity()).isEqualTo(AGENT_PROPS.claimBatchSize());
        }

        @Test
        @DisplayName("free capacity is reviewMax minus jobs already running locally")
        void freeCapacityIsReviewMaxMinusLocalRunning() throws Exception {
            de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerCapacityState capacityState =
                    new de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerCapacityState(workerProps("w"));
            capacityState.claimReview();
            capacityState.claimReview(); // 2 in flight; reviewMax is 2 (see workerProps)

            executor = new AgentJobExecutor(
                    AGENT_PROPS,
                    jobRepository,
                    bindingRepository,
                    handlerRegistry,
                    practiceAgent,
                    workerJwtIssuer,
                    sandboxManager,
                    sandboxExecutor,
                    transactionTemplate,
                    objectMapper,
                    meterRegistry,
                    new PracticeReviewRefusalMetrics(meterRegistry),
                    new AgentJobTelemetry(meterRegistry),
                    usageRecorder,
                    llmBudgetService,
                    NO_LIVE_ADMISSION,
                    Optional.of(capacityState),
                    Optional.empty());
            // Mirror the two claimReview() calls above by populating localRunningJobs directly —
            // computeCapacity reads localRunningJobs.size(), not the capacity state's own counter.
            Set<UUID> localRunningJobs = heldJobs();
            localRunningJobs.add(UUID.randomUUID());
            localRunningJobs.add(UUID.randomUUID());

            // reviewMax (2) - localRunning (2) = 0 free capacity.
            assertThat(executor.computeCapacity()).isZero();
        }

        @Test
        @DisplayName("capacity is bounded by claimBatchSize even when the pool has more room")
        void capacityIsBoundedByClaimBatchSize() {
            AgentProperties smallBatch = new AgentProperties(
                    true,
                    Duration.ofSeconds(1),
                    2,
                    5,
                    Duration.ofSeconds(25),
                    Duration.ofDays(14),
                    Duration.ofDays(90));
            de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerCapacityState capacityState =
                    new de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerCapacityState(new WorkerProperties(
                            "w",
                            new WorkerProperties.Capacity("10", "1"), // reviewMax=10, far above claimBatchSize=2
                            new WorkerProperties.Drain(Duration.ofMinutes(5)),
                            new WorkerProperties.Heartbeat(Duration.ofSeconds(20)),
                            new WorkerProperties.Control(URI.create("ws://example"), "tok", Duration.ofSeconds(10))));

            executor = new AgentJobExecutor(
                    smallBatch,
                    jobRepository,
                    bindingRepository,
                    handlerRegistry,
                    practiceAgent,
                    workerJwtIssuer,
                    sandboxManager,
                    sandboxExecutor,
                    transactionTemplate,
                    objectMapper,
                    meterRegistry,
                    new PracticeReviewRefusalMetrics(meterRegistry),
                    new AgentJobTelemetry(meterRegistry),
                    usageRecorder,
                    llmBudgetService,
                    NO_LIVE_ADMISSION,
                    Optional.of(capacityState),
                    Optional.empty());

            assertThat(executor.computeCapacity()).isEqualTo(2);
        }

        @Test
        @DisplayName("empty candidate list means no claim is even attempted")
        void emptyPollAttemptsNoClaims() throws Exception {
            var polled = new CountDownLatch(1);
            when(jobRepository.findQueuedIdsOldestFirst(anyInt())).thenAnswer(invocation -> {
                polled.countDown();
                return List.of();
            });

            executor.start();
            try {
                assertThat(polled.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                executor.stopAcceptingNewJobs();
            }

            verify(jobRepository, atLeastOnce()).findQueuedIdsOldestFirst(anyInt());
            verify(jobRepository, never()).findByIdQueuedForUpdateSkipLocked(any(), any());
        }

        @Test
        @DisplayName("capacity is further bounded by the sandbox executor's pool size — reviewMax "
                + "alone is not enough, it can exceed the pool size")
        void capacityIsBoundedBySandboxExecutorPoolSize() throws Exception {
            org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor realPool =
                    new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
            realPool.setCorePoolSize(1);
            realPool.setMaxPoolSize(2); // pool cap of 2, far below reviewMax (10) and claimBatchSize (5)
            realPool.setQueueCapacity(0);
            realPool.initialize();
            try {
                de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerCapacityState capacityState =
                        new de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerCapacityState(new WorkerProperties(
                                "w",
                                new WorkerProperties.Capacity("10", "1"),
                                new WorkerProperties.Drain(Duration.ofMinutes(5)),
                                new WorkerProperties.Heartbeat(Duration.ofSeconds(20)),
                                new WorkerProperties.Control(
                                        URI.create("ws://example"), "tok", Duration.ofSeconds(10))));

                executor = new AgentJobExecutor(
                        AGENT_PROPS,
                        jobRepository,
                        bindingRepository,
                        handlerRegistry,
                        practiceAgent,
                        workerJwtIssuer,
                        sandboxManager,
                        realPool,
                        transactionTemplate,
                        objectMapper,
                        meterRegistry,
                        new PracticeReviewRefusalMetrics(meterRegistry),
                        new AgentJobTelemetry(meterRegistry),
                        usageRecorder,
                        llmBudgetService,
                        NO_LIVE_ADMISSION,
                        Optional.of(capacityState),
                        Optional.empty());

                // Nothing active yet: bounded by the pool's max size (2), not reviewMax (10) or
                // claimBatchSize (5, AGENT_PROPS's default).
                assertThat(executor.computeCapacity()).isEqualTo(2);
            } finally {
                realPool.shutdown();
            }
        }

        @Test
        @DisplayName("a job this worker already holds occupies a pool slot, whether or not the pool "
                + "thread has entered it yet")
        void heldJobsOccupyPoolSlots() throws Exception {
            org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor realPool =
                    new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
            realPool.setCorePoolSize(1);
            realPool.setMaxPoolSize(2);
            realPool.setQueueCapacity(0);
            realPool.initialize();
            try {
                de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerCapacityState capacityState =
                        new de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerCapacityState(new WorkerProperties(
                                "w",
                                new WorkerProperties.Capacity("10", "1"),
                                new WorkerProperties.Drain(Duration.ofMinutes(5)),
                                new WorkerProperties.Heartbeat(Duration.ofSeconds(20)),
                                new WorkerProperties.Control(
                                        URI.create("ws://example"), "tok", Duration.ofSeconds(10))));

                executor = new AgentJobExecutor(
                        AGENT_PROPS,
                        jobRepository,
                        bindingRepository,
                        handlerRegistry,
                        practiceAgent,
                        workerJwtIssuer,
                        sandboxManager,
                        realPool,
                        transactionTemplate,
                        objectMapper,
                        meterRegistry,
                        new PracticeReviewRefusalMetrics(meterRegistry),
                        new AgentJobTelemetry(meterRegistry),
                        usageRecorder,
                        llmBudgetService,
                        NO_LIVE_ADMISSION,
                        Optional.of(capacityState),
                        Optional.empty());

                Set<UUID> localRunningJobs = heldJobs();

                // The pool reports no busy thread; the jobs this worker holds are what fills it.
                localRunningJobs.add(UUID.randomUUID());
                assertThat(realPool.getActiveCount()).isZero();
                assertThat(executor.computeCapacity()).isEqualTo(1);

                localRunningJobs.add(UUID.randomUUID());
                assertThat(executor.computeCapacity()).isZero();
            } finally {
                realPool.shutdown();
            }
        }
    }

    @Nested
    @DisplayName("Sandbox pool rejection")
    class PoolRejection {

        @Test
        @DisplayName("a pool-rejected claim is requeued WITHOUT incrementing retry_count, self-fenced to this worker")
        void requeuesWithoutRetryIncrementSelfFenced() {
            executor = new AgentJobExecutor(
                    AGENT_PROPS,
                    jobRepository,
                    bindingRepository,
                    handlerRegistry,
                    practiceAgent,
                    workerJwtIssuer,
                    sandboxManager,
                    sandboxExecutor,
                    transactionTemplate,
                    objectMapper,
                    meterRegistry,
                    new PracticeReviewRefusalMetrics(meterRegistry),
                    new AgentJobTelemetry(meterRegistry),
                    usageRecorder,
                    llmBudgetService,
                    NO_LIVE_ADMISSION,
                    Optional.empty(),
                    Optional.of(workerProps("rejecting-worker")));

            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            doThrow(new java.util.concurrent.RejectedExecutionException("pool saturated"))
                    .when(sandboxExecutor)
                    .execute(any());

            boolean claimed = executor.processJob(jobId);

            assertThat(claimed).isTrue(); // claim itself won; dispatch was rejected afterwards
            verify(jobRepository).requeueRejectedClaim(jobId, "rejecting-worker");
            verify(jobRepository, never()).requeueOrphan(any(), any(), anyInt(), any(), any(), any());
            verify(sandboxManager, never()).execute(any());
        }

        @Test
        @DisplayName("retries the requeue write a bounded number of times before giving up")
        void retriesTheRequeueWriteOnTransientFailureButWritesOnlyOnce() {
            executor = new AgentJobExecutor(
                    AGENT_PROPS,
                    jobRepository,
                    bindingRepository,
                    handlerRegistry,
                    practiceAgent,
                    workerJwtIssuer,
                    sandboxManager,
                    sandboxExecutor,
                    transactionTemplate,
                    objectMapper,
                    meterRegistry,
                    new PracticeReviewRefusalMetrics(meterRegistry),
                    new AgentJobTelemetry(meterRegistry),
                    usageRecorder,
                    llmBudgetService,
                    NO_LIVE_ADMISSION,
                    Optional.empty(),
                    Optional.of(workerProps("rejecting-worker")));

            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            doThrow(new java.util.concurrent.RejectedExecutionException("pool saturated"))
                    .when(sandboxExecutor)
                    .execute(any());
            java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(inv -> {
                        if (attempts.incrementAndGet() <= 2) {
                            throw new org.springframework.dao.TransientDataAccessResourceException("blip");
                        }
                        @SuppressWarnings("unchecked")
                        java.util.function.Consumer<TransactionStatus> callback = inv.getArgument(0);
                        callback.accept(mock(TransactionStatus.class));
                        return null;
                    })
                    .when(transactionTemplate)
                    .executeWithoutResult(any());

            executor.processJob(jobId);

            // 3 transaction attempts (2 failed, 1 succeeded); the underlying repository write only
            // actually happens on the attempt whose transaction callback ran.
            verify(transactionTemplate, org.mockito.Mockito.times(3)).executeWithoutResult(any());
            verify(jobRepository, org.mockito.Mockito.times(1)).requeueRejectedClaim(jobId, "rejecting-worker");
        }
    }

    @Nested
    @DisplayName("Drain requeue-first — matches the documented drain contract")
    class DrainRequeue {

        @Test
        @DisplayName("draining an in-flight job requeues it (RUNNING -> QUEUED) instead of cancelling it")
        void drainRequeuesInsteadOfCancelling() throws Exception {
            executor = new AgentJobExecutor(
                    AGENT_PROPS,
                    jobRepository,
                    bindingRepository,
                    handlerRegistry,
                    practiceAgent,
                    workerJwtIssuer,
                    sandboxManager,
                    sandboxExecutor,
                    transactionTemplate,
                    objectMapper,
                    meterRegistry,
                    new PracticeReviewRefusalMetrics(meterRegistry),
                    new AgentJobTelemetry(meterRegistry),
                    usageRecorder,
                    llmBudgetService,
                    NO_LIVE_ADMISSION,
                    Optional.empty(),
                    Optional.of(workerProps("draining-worker")));
            addToLocalRunningJobs(executor, jobId);

            when(jobRepository.requeueOrphan(
                            eq(jobId), eq("draining-worker"), eq(AGENT_PROPS.maxRetries()), any(), any(), any()))
                    .thenReturn(1);
            when(jobRepository.findByIdWithWorkspaceForUpdate(jobId)).thenReturn(Optional.of(job));

            executor.cancelInFlight(AgentJobCancellationReason.DRAIN_GRACEFUL);

            verify(jobRepository)
                    .requeueOrphan(eq(jobId), eq("draining-worker"), eq(AGENT_PROPS.maxRetries()), any(), any(), any());
            verify(jobRepository, never()).transitionToCancelledOwnedBy(any(), any(), any(), any(), any(), any());
            verify(jobRepository, never()).transitionToCancelled(any(), any(), any(), any(), any());
            verify(sandboxManager).cancel(jobId);
        }

        @Test
        @DisplayName(
                "falls back to a worker-fenced terminal cancel when the requeue CAS loses (retry cap exhausted / fence lost)")
        void fallsBackToFencedCancelWhenRequeueLoses() throws Exception {
            executor = new AgentJobExecutor(
                    AGENT_PROPS,
                    jobRepository,
                    bindingRepository,
                    handlerRegistry,
                    practiceAgent,
                    workerJwtIssuer,
                    sandboxManager,
                    sandboxExecutor,
                    transactionTemplate,
                    objectMapper,
                    meterRegistry,
                    new PracticeReviewRefusalMetrics(meterRegistry),
                    new AgentJobTelemetry(meterRegistry),
                    usageRecorder,
                    llmBudgetService,
                    NO_LIVE_ADMISSION,
                    Optional.empty(),
                    Optional.of(workerProps("draining-worker")));
            addToLocalRunningJobs(executor, jobId);

            when(jobRepository.requeueOrphan(
                            eq(jobId), eq("draining-worker"), eq(AGENT_PROPS.maxRetries()), any(), any(), any()))
                    .thenReturn(0);
            when(jobRepository.findByIdWithWorkspaceForUpdate(jobId)).thenReturn(Optional.of(job));

            executor.cancelInFlight(AgentJobCancellationReason.DRAIN_GRACEFUL);

            verify(jobRepository)
                    .transitionToCancelledOwnedBy(
                            eq(jobId),
                            any(),
                            any(),
                            eq(AgentJobCancellationReason.DRAIN_GRACEFUL),
                            eq(Set.of(AgentJobStatus.RUNNING)),
                            eq("draining-worker"));
            verify(sandboxManager).cancel(jobId);
        }

        @SuppressWarnings("unchecked")
        private void addToLocalRunningJobs(AgentJobExecutor exec, UUID id) throws Exception {
            java.lang.reflect.Field field = AgentJobExecutor.class.getDeclaredField("localRunningJobs");
            field.setAccessible(true);
            ((Set<UUID>) field.get(exec)).add(id);
        }
    }

    @Nested
    @DisplayName("Drain admission race")
    class DrainAdmissionRace {

        @Test
        @DisplayName("stopAcceptingNewJobs() joins the poll thread before returning — no thread left running")
        void stopAcceptingNewJobsJoinsThePollThread() {
            lenient().when(jobRepository.findQueuedIdsOldestFirst(anyInt())).thenReturn(List.of());

            executor.start();
            try {
                assertThat(threadIsAlive(executor)).isTrue();
            } finally {
                executor.stopAcceptingNewJobs();
            }

            assertThat(threadIsAlive(executor)).isFalse();
        }

        private boolean threadIsAlive(AgentJobExecutor exec) {
            try {
                java.lang.reflect.Field field = AgentJobExecutor.class.getDeclaredField("pollThread");
                field.setAccessible(true);
                Thread thread = (Thread) field.get(exec);
                return thread != null && thread.isAlive();
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Nested
    @DisplayName("Execution-start fence")
    class ExecutionStartFence {

        @Test
        void lostFenceAfterPreparationNeverStartsSandboxOrWritesUsage() {
            when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any()))
                    .thenReturn(Optional.of(job));
            when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.of(binding));
            when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(
                            eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                    .thenReturn(0L);
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.markExecutionStarted(any(), any(), any())).thenReturn(0);
            when(jobRepository.updateProvenanceDigests(any(), any(), anyInt(), any()))
                    .thenReturn(1);
            JobTypeHandler handler = mock(JobTypeHandler.class);
            when(handlerRegistry.getHandler(AgentJobType.PULL_REQUEST_REVIEW)).thenReturn(handler);
            when(handler.prepareInputs(any())).thenReturn(PreparedJobInputs.filesOnly(Map.of()));
            when(practiceAgent.buildSandboxSpec(any())).thenReturn(minimalSpec());

            executor.processJob(jobId);

            verify(sandboxManager, never()).execute(any());
            verify(usageRecorder, never()).record(any(), any());
            verify(usageRecorder, never()).recordUnverifiable(any(), any());
        }
    }

    private void stubClaimableJob() {
        when(jobRepository.findByIdQueuedForUpdateSkipLocked(eq(jobId), any())).thenReturn(Optional.of(job));
        when(bindingRepository.findByWorkspaceIdAndPurpose(99L, AgentPurpose.PRACTICE_REVIEW))
                .thenReturn(Optional.of(binding));
        when(jobRepository.countByWorkspaceIdAndPurposeAndStatusIn(eq(99L), eq(AgentPurpose.PRACTICE_REVIEW), any()))
                .thenReturn(0L);
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot pricedSnapshot() {
        return new de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot(
                de.tum.cit.aet.hephaestus.agent.usage.FundingSource.INSTANCE,
                de.tum.cit.aet.hephaestus.agent.usage.PricingState.PRICED,
                1L,
                null,
                new java.math.BigDecimal("1.00"),
                new java.math.BigDecimal("2.00"),
                new java.math.BigDecimal("0.10"),
                new java.math.BigDecimal("0.20"));
    }

    private JobTypeHandler setupFullExecution() {
        SandboxResult successResult = new SandboxResult(0, Map.of(), "success", false, Duration.ofMinutes(2));
        return setupFullExecution(successResult);
    }

    private JobTypeHandler setupFullExecution(SandboxResult sandboxResult) {
        // Every execution stamps its provenance digests before the sandbox starts, and fails loud if the write
        // matches no row — so the standard path must report the row it updated.
        lenient()
                .when(jobRepository.updateProvenanceDigests(any(), any(), anyInt(), any()))
                .thenReturn(1);
        JobTypeHandler handler = mock(JobTypeHandler.class);
        when(handlerRegistry.getHandler(AgentJobType.PULL_REQUEST_REVIEW)).thenReturn(handler);
        when(handler.prepareInputs(any()))
                .thenReturn(PreparedJobInputs.filesOnly(Map.of("code.py", "print('hi')".getBytes())));

        PracticeSandboxSpec agentSpec = new PracticeSandboxSpec(
                "ghcr.io/agent:latest",
                List.of("/bin/agent"),
                Map.of("KEY", "value"),
                Map.of("config.json", "{}".getBytes()),
                "/output",
                SecurityProfile.DEFAULT,
                new NetworkPolicy(false, null, "test-token"),
                Map.of(),
                "prompt-digest");
        when(practiceAgent.buildSandboxSpec(any())).thenReturn(agentSpec);
        when(practiceAgent.parseResult(any())).thenReturn(new AgentResult(true, Map.of("review", "LGTM")));

        when(sandboxManager.execute(any())).thenReturn(sandboxResult);
        return handler;
    }

    private static PracticeSandboxSpec minimalSpec() {
        return new PracticeSandboxSpec(
                "ghcr.io/agent:latest",
                List.of("/bin/agent"),
                Map.of(),
                Map.of(),
                "/output",
                null,
                null,
                Map.of(),
                "prompt-digest");
    }

    private static WorkerProperties workerProps(String workerId) {
        return new WorkerProperties(
                workerId,
                new WorkerProperties.Capacity("2", "1"),
                new WorkerProperties.Drain(Duration.ofMinutes(5)),
                new WorkerProperties.Heartbeat(Duration.ofSeconds(20)),
                new WorkerProperties.Control(URI.create("ws://example"), "tok", Duration.ofSeconds(10)));
    }

    private void setupFullExecutionWithException(Exception exception) {
        lenient()
                .when(jobRepository.updateProvenanceDigests(any(), any(), anyInt(), any()))
                .thenReturn(1);
        JobTypeHandler handler = mock(JobTypeHandler.class);
        when(handlerRegistry.getHandler(AgentJobType.PULL_REQUEST_REVIEW)).thenReturn(handler);
        when(handler.prepareInputs(any())).thenReturn(PreparedJobInputs.filesOnly(Map.of()));

        PracticeSandboxSpec agentSpec = new PracticeSandboxSpec(
                "ghcr.io/agent:latest",
                List.of("/bin/agent"),
                Map.of(),
                Map.of(),
                "/output",
                null,
                null,
                Map.of(),
                null);
        when(practiceAgent.buildSandboxSpec(any())).thenReturn(agentSpec);

        when(sandboxManager.execute(any())).thenThrow(exception);
    }
}
