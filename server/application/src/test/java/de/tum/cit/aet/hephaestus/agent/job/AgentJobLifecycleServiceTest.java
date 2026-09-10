package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.handler.JobTypeHandlerRegistry;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ExistingDeliveryLookup;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobTypeHandler;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxManager;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.PricingState;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class AgentJobLifecycleServiceTest extends BaseUnitTest {

    @Mock
    private AgentJobRepository agentJobRepository;

    @Mock
    private JobTypeHandlerRegistry handlerRegistry;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private SandboxManager sandboxManager;

    @Mock
    private LlmUsageRecorder usageRecorder;

    @Mock
    private FeedbackDispatchRepository feedbackDispatchRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AgentJobLifecycleService service;

    private Workspace workspace;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new AgentJobLifecycleService(
                agentJobRepository,
                handlerRegistry,
                transactionTemplate,
                sandboxManager,
                Optional.empty(),
                usageRecorder,
                objectMapper,
                feedbackDispatchRepository,
                new AgentJobTelemetry(new SimpleMeterRegistry(), io.micrometer.tracing.Tracer.NOOP));

        workspace = new Workspace();
        workspace.setId(1L);
        workspace.setWorkspaceSlug("test-ws");

        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        lenient()
                .doAnswer(inv -> {
                    Consumer<TransactionStatus> action = inv.getArgument(0);
                    action.accept(mock(TransactionStatus.class));
                    return null;
                })
                .when(transactionTemplate)
                .executeWithoutResult(any());
    }

    private AgentJob createJobWithStatus(AgentJobStatus status) {
        AgentJob job = new AgentJob();
        job.prePersist();
        job.setWorkspace(workspace);
        job.setStatus(status);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        ConfigSnapshot snapshot = new ConfigSnapshot(
                        ConfigSnapshot.SCHEMA_VERSION,
                        "openai-completions",
                        "https://api.openai.com/v1",
                        "test-model",
                        null,
                        null,
                        null,
                        false,
                        FundingSource.INSTANCE,
                        1L,
                        1L,
                        null,
                        600,
                        false,
                        null)
                .withPriceSnapshot(new LlmPriceSnapshot(
                        FundingSource.INSTANCE, PricingState.NO_CHARGE, null, null, null, null, null, null));
        job.setConfigSnapshot(snapshot.toJson(objectMapper));
        return job;
    }

    @Nested
    class Cancel {

        @Test
        void shouldCancelQueuedJob() {
            AgentJob job = createJobWithStatus(AgentJobStatus.QUEUED);
            UUID jobId = job.getId();

            when(agentJobRepository.findByIdAndWorkspaceId(jobId, 1L)).thenReturn(Optional.of(job));
            when(agentJobRepository.transitionStatus(eq(jobId), eq(AgentJobStatus.CANCELLED), any(), any(), any()))
                    .thenReturn(1);

            AgentJob cancelledJob = createJobWithStatus(AgentJobStatus.CANCELLED);
            when(agentJobRepository.findByIdAndWorkspaceId(jobId, 1L))
                    .thenReturn(Optional.of(job))
                    .thenReturn(Optional.of(cancelledJob));

            AgentJob result = service.cancel(1L, jobId);

            assertThat(result.getStatus()).isEqualTo(AgentJobStatus.CANCELLED);
        }

        @Test
        void shouldCancelRunningJobAndCallSandbox() {
            AgentJob job = createJobWithStatus(AgentJobStatus.RUNNING);
            UUID jobId = job.getId();

            when(agentJobRepository.findByIdAndWorkspaceId(jobId, 1L)).thenReturn(Optional.of(job));
            when(agentJobRepository.transitionStatus(eq(jobId), eq(AgentJobStatus.CANCELLED), any(), any(), any()))
                    .thenReturn(1);

            AgentJob cancelledJob = createJobWithStatus(AgentJobStatus.CANCELLED);
            when(agentJobRepository.findByIdAndWorkspaceId(jobId, 1L))
                    .thenReturn(Optional.of(job))
                    .thenReturn(Optional.of(cancelledJob));

            AgentJob result = service.cancel(1L, jobId);

            assertThat(result.getStatus()).isEqualTo(AgentJobStatus.CANCELLED);
            verify(sandboxManager).cancel(jobId);
        }

        @Test
        void shouldBeIdempotentForCancelledJob() {
            AgentJob job = createJobWithStatus(AgentJobStatus.CANCELLED);
            UUID jobId = job.getId();

            when(agentJobRepository.findByIdAndWorkspaceId(jobId, 1L)).thenReturn(Optional.of(job));

            AgentJob result = service.cancel(1L, jobId);

            assertThat(result.getStatus()).isEqualTo(AgentJobStatus.CANCELLED);
            verify(agentJobRepository, never()).transitionStatus(any(), any(), any(), any(), any());
        }

        @ParameterizedTest
        @EnumSource(
                value = AgentJobStatus.class,
                names = {"COMPLETED", "FAILED", "TIMED_OUT"})
        void shouldThrow409ForAnAlreadyTerminalJob(AgentJobStatus terminalStatus) {
            AgentJob job = createJobWithStatus(terminalStatus);
            UUID jobId = job.getId();

            when(agentJobRepository.findByIdAndWorkspaceId(jobId, 1L)).thenReturn(Optional.of(job));

            assertThatThrownBy(() -> service.cancel(1L, jobId))
                    .isInstanceOf(AgentJobStateConflictException.class)
                    .hasMessageContaining(terminalStatus.name());
        }

        @Test
        void shouldThrow404ForNonExistentJob() {
            UUID jobId = UUID.randomUUID();
            when(agentJobRepository.findByIdAndWorkspaceId(jobId, 1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.cancel(1L, jobId)).isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        void shouldThrow409WhenExecutorWinsRace() {
            AgentJob job = createJobWithStatus(AgentJobStatus.RUNNING);
            UUID jobId = job.getId();

            when(agentJobRepository.findByIdAndWorkspaceId(jobId, 1L)).thenReturn(Optional.of(job));
            when(agentJobRepository.transitionStatus(any(), any(), any(), any(), any()))
                    .thenReturn(0);

            AgentJob completedJob = createJobWithStatus(AgentJobStatus.COMPLETED);
            when(agentJobRepository.findByIdAndWorkspaceId(jobId, 1L))
                    .thenReturn(Optional.of(job))
                    .thenReturn(Optional.of(completedJob));

            assertThatThrownBy(() -> service.cancel(1L, jobId))
                    .isInstanceOf(AgentJobStateConflictException.class)
                    .hasMessageContaining("COMPLETED");

            verify(sandboxManager, never()).cancel(any());
        }
    }

    @Nested
    @DisplayName("Unpriced usage ledger write on user-cancel")
    class UnverifiableUsageLedger {

        @Test
        void cancellingAClaimedRunningJob_recordsAnUnpricedLedgerEntry() {
            AgentJob job = createJobWithStatus(AgentJobStatus.RUNNING);
            job.setWorkerId("worker-1");
            job.setExecutionStartedAt(java.time.Instant.now());
            UUID jobId = job.getId();

            when(agentJobRepository.findByIdAndWorkspaceId(jobId, 1L)).thenReturn(Optional.of(job));
            when(agentJobRepository.transitionStatus(eq(jobId), eq(AgentJobStatus.CANCELLED), any(), any(), any()))
                    .thenReturn(1);
            AgentJob cancelledJob = createJobWithStatus(AgentJobStatus.CANCELLED);
            cancelledJob.setId(jobId);
            cancelledJob.setWorkerId("worker-1");
            cancelledJob.setExecutionStartedAt(job.getExecutionStartedAt());
            when(agentJobRepository.findByIdAndWorkspaceId(jobId, 1L))
                    .thenReturn(Optional.of(job))
                    .thenReturn(Optional.of(cancelledJob));

            service.cancel(1L, jobId);

            ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                    ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(usageRecorder).recordUnverifiable(eq(1L), sample.capture());
            assertThat(sample.getValue().sourceId()).isEqualTo(cancelledJob.getId());
            assertThat(sample.getValue().sourceAttempt()).isEqualTo(cancelledJob.getRetryCount());
            assertThat(sample.getValue().inputTokens()).isZero();
        }

        @Test
        void cancellingALegacyClaimedJobWithoutAdmissionPrice_preservesCancellationAndRecordsUnpricedUsage() {
            AgentJob job = createJobWithStatus(AgentJobStatus.RUNNING);
            job.setWorkerId("worker-1");
            job.setExecutionStartedAt(java.time.Instant.now());
            ConfigSnapshot legacySnapshot = ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper)
                    .withPriceSnapshot(null);
            job.setConfigSnapshot(legacySnapshot.toJson(objectMapper));
            UUID jobId = job.getId();

            when(agentJobRepository.findByIdAndWorkspaceId(jobId, 1L)).thenReturn(Optional.of(job));
            when(agentJobRepository.transitionStatus(eq(jobId), eq(AgentJobStatus.CANCELLED), any(), any(), any()))
                    .thenReturn(1);
            AgentJob cancelledJob = createJobWithStatus(AgentJobStatus.CANCELLED);
            cancelledJob.setId(jobId);
            cancelledJob.setWorkerId("worker-1");
            cancelledJob.setExecutionStartedAt(job.getExecutionStartedAt());
            cancelledJob.setConfigSnapshot(legacySnapshot.toJson(objectMapper));
            when(agentJobRepository.findByIdAndWorkspaceId(jobId, 1L))
                    .thenReturn(Optional.of(job))
                    .thenReturn(Optional.of(cancelledJob));

            AgentJob result = service.cancel(1L, jobId);

            assertThat(result.getStatus()).isEqualTo(AgentJobStatus.CANCELLED);
            ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                    ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(usageRecorder).recordUnverifiable(eq(1L), sample.capture());
            assertThat(sample.getValue().price().fundingSource()).isEqualTo(FundingSource.INSTANCE);
            assertThat(sample.getValue().price().pricingState()).isEqualTo(PricingState.UNPRICED);
        }

        @Test
        void cancellingAJobThatNeverStarted_neverTouchesTheLedger() {
            AgentJob job = createJobWithStatus(AgentJobStatus.QUEUED);
            UUID jobId = job.getId();

            when(agentJobRepository.findByIdAndWorkspaceId(jobId, 1L)).thenReturn(Optional.of(job));
            when(agentJobRepository.transitionStatus(eq(jobId), eq(AgentJobStatus.CANCELLED), any(), any(), any()))
                    .thenReturn(1);
            AgentJob cancelledJob = createJobWithStatus(AgentJobStatus.CANCELLED);
            when(agentJobRepository.findByIdAndWorkspaceId(jobId, 1L))
                    .thenReturn(Optional.of(job))
                    .thenReturn(Optional.of(cancelledJob));

            service.cancel(1L, jobId);

            verify(usageRecorder, never()).recordUnverifiable(any(), any());
        }

        @Test
        void cancellingAClaimedJobStillInPreparation_neverTouchesTheLedger() {
            AgentJob job = createJobWithStatus(AgentJobStatus.RUNNING);
            job.setWorkerId("worker-1");
            UUID jobId = job.getId();

            when(agentJobRepository.findByIdAndWorkspaceId(jobId, 1L)).thenReturn(Optional.of(job));
            when(agentJobRepository.transitionStatus(eq(jobId), eq(AgentJobStatus.CANCELLED), any(), any(), any()))
                    .thenReturn(1);
            AgentJob cancelledJob = createJobWithStatus(AgentJobStatus.CANCELLED);
            cancelledJob.setId(jobId);
            cancelledJob.setWorkerId("worker-1");
            when(agentJobRepository.findByIdAndWorkspaceId(jobId, 1L))
                    .thenReturn(Optional.of(job))
                    .thenReturn(Optional.of(cancelledJob));

            service.cancel(1L, jobId);

            verify(usageRecorder, never()).recordUnverifiable(any(), any());
        }

        @Test
        void idempotentCancelOfAnAlreadyCancelledStartedJob_doesNotWriteOutsideWinningTransaction() {
            AgentJob job = createJobWithStatus(AgentJobStatus.CANCELLED);
            job.setWorkerId("worker-1");
            UUID jobId = job.getId();

            when(agentJobRepository.findByIdAndWorkspaceId(jobId, 1L)).thenReturn(Optional.of(job));

            service.cancel(1L, jobId);

            verify(usageRecorder, never()).recordUnverifiable(any(), any());
            verify(agentJobRepository, never()).transitionStatus(any(), any(), any(), any(), any());
        }
    }

    @Nested
    class RetryDelivery {

        private static final Long WORKSPACE_ID = 1L;

        private AgentJob completedJob;
        private UUID jobId;
        private JobTypeHandler handler;

        @BeforeEach
        void setUpRetryDelivery() {
            // transactionTemplate.execute()/executeWithoutResult() already invoke their
            // callback/consumer via the lenient stubs in the outer setUp() — re-stubbing the same
            // methods here would re-trigger those stubs during when()'s "record" phase (Mockito
            // invokes the mock to register the matcher, and an already-answered method runs its
            // existing answer with a null argument) and NPE.

            completedJob = createJobWithStatus(AgentJobStatus.COMPLETED);
            jobId = completedJob.getId();

            handler = mock(JobTypeHandler.class);
            lenient()
                    .when(handlerRegistry.getHandler(AgentJobType.PULL_REQUEST_REVIEW))
                    .thenReturn(handler);
        }

        @Test
        void shouldThrow404WhenJobNotFound() {
            when(agentJobRepository.findByIdAndWorkspaceId(jobId, WORKSPACE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.retryDelivery(WORKSPACE_ID, jobId))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("AgentJob");
        }

        @Test
        void shouldThrow409WhenCasTransitionReturnsZero() {
            when(agentJobRepository.findByIdAndWorkspaceId(jobId, WORKSPACE_ID)).thenReturn(Optional.of(completedJob));
            when(agentJobRepository.transitionDeliveryStatus(eq(jobId), eq(DeliveryStatus.PENDING), any()))
                    .thenReturn(0);

            assertThatThrownBy(() -> service.retryDelivery(WORKSPACE_ID, jobId))
                    .isInstanceOf(AgentJobStateConflictException.class)
                    .hasMessageContaining("delivery status FAILED");

            verify(handler, never()).deliver(any());
        }

        @Test
        void shouldDeliverAndUpdateStatusOnSuccess() {
            when(agentJobRepository.findByIdAndWorkspaceId(jobId, WORKSPACE_ID)).thenReturn(Optional.of(completedJob));
            when(agentJobRepository.transitionDeliveryStatus(eq(jobId), eq(DeliveryStatus.PENDING), any()))
                    .thenReturn(1);
            when(agentJobRepository.findById(jobId)).thenReturn(Optional.of(completedJob));

            AgentJob result = service.retryDelivery(WORKSPACE_ID, jobId);

            verify(feedbackDispatchRepository).resetFailedAutomaticPackage(jobId, WORKSPACE_ID);
            verify(handler).deliver(completedJob);
            verify(agentJobRepository)
                    .updateDeliveryStatus(
                            eq(jobId), eq(DeliveryStatus.DELIVERED), eq(completedJob.getDeliveryCommentId()));
            assertThat(result.getId()).isEqualTo(jobId);
        }

        @Test
        void shouldRevertToFailedOnDeliveryException() {
            when(agentJobRepository.findByIdAndWorkspaceId(jobId, WORKSPACE_ID)).thenReturn(Optional.of(completedJob));
            when(agentJobRepository.transitionDeliveryStatus(eq(jobId), eq(DeliveryStatus.PENDING), any()))
                    .thenReturn(1);
            when(agentJobRepository.findById(jobId)).thenReturn(Optional.of(completedJob));

            doThrow(new RuntimeException("GitHub API rate limited"))
                    .when(handler)
                    .deliver(completedJob);

            assertThatThrownBy(() -> service.retryDelivery(WORKSPACE_ID, jobId))
                    .isInstanceOf(AgentJobStateConflictException.class)
                    .hasMessageContaining("Delivery retry failed")
                    .hasMessageContaining("GitHub API rate limited");

            verify(agentJobRepository)
                    .updateDeliveryStatus(
                            eq(jobId), eq(DeliveryStatus.FAILED), eq(completedJob.getDeliveryCommentId()));
        }
    }

    @Nested
    @DisplayName("recoverStuckDelivery")
    class RecoverStuckDelivery {

        private static final short CLAIMED_ATTEMPTS = 1;

        private AgentJob completedJob;
        private UUID jobId;
        private JobTypeHandler handler;

        @BeforeEach
        void setUpRecovery() {
            completedJob = createJobWithStatus(AgentJobStatus.COMPLETED);
            completedJob.setDeliveryStatus(DeliveryStatus.PENDING);
            jobId = completedJob.getId();

            handler = mock(JobTypeHandler.class);
            lenient()
                    .when(handlerRegistry.getHandler(AgentJobType.PULL_REQUEST_REVIEW))
                    .thenReturn(handler);
        }

        @Test
        @DisplayName("a FOUND marker hit records the existing comment id as DELIVERED WITHOUT calling deliver() again")
        void markerHitSkipsRedelivery() {
            lenient()
                    .when(handler.findExistingDelivery(completedJob))
                    .thenReturn(ExistingDeliveryLookup.found("existing-comment-id"));
            lenient()
                    .when(agentJobRepository.transitionDeliveryStatusFenced(
                            eq(jobId),
                            eq(DeliveryStatus.DELIVERED),
                            eq("existing-comment-id"),
                            any(),
                            eq(CLAIMED_ATTEMPTS)))
                    .thenReturn(1);

            boolean result = service.recoverStuckDelivery(completedJob, CLAIMED_ATTEMPTS);

            assertThat(result).isTrue();
            verify(handler, never()).deliver(any());
            verify(agentJobRepository)
                    .transitionDeliveryStatusFenced(
                            jobId,
                            DeliveryStatus.DELIVERED,
                            "existing-comment-id",
                            java.util.Set.of(DeliveryStatus.PENDING),
                            CLAIMED_ATTEMPTS);
        }

        @Test
        void existingSummaryStillReconcilesTheRestOfAProviderPackage() {
            when(handler.findExistingDelivery(completedJob))
                    .thenReturn(ExistingDeliveryLookup.found("existing-comment-id"));
            when(handler.reconcilesMoreThanOneProviderObject()).thenReturn(true);
            when(agentJobRepository.transitionDeliveryStatusFenced(
                            eq(jobId), eq(DeliveryStatus.DELIVERED), any(), any(), eq(CLAIMED_ATTEMPTS)))
                    .thenReturn(1);

            boolean result = service.recoverStuckDelivery(completedJob, CLAIMED_ATTEMPTS);

            assertThat(result).isTrue();
            verify(handler).deliver(completedJob);
        }

        @Test
        @DisplayName("ABSENT falls through to a normal deliver() attempt, which succeeds")
        void absentFallsThroughToDeliverAndSucceeds() {
            lenient().when(handler.findExistingDelivery(completedJob)).thenReturn(ExistingDeliveryLookup.absent());
            lenient()
                    .when(agentJobRepository.transitionDeliveryStatusFenced(
                            eq(jobId), eq(DeliveryStatus.DELIVERED), any(), any(), eq(CLAIMED_ATTEMPTS)))
                    .thenReturn(1);

            boolean result = service.recoverStuckDelivery(completedJob, CLAIMED_ATTEMPTS);

            assertThat(result).isTrue();
            verify(handler).deliver(completedJob);
            verify(agentJobRepository)
                    .transitionDeliveryStatusFenced(
                            jobId,
                            DeliveryStatus.DELIVERED,
                            completedJob.getDeliveryCommentId(),
                            java.util.Set.of(DeliveryStatus.PENDING),
                            CLAIMED_ATTEMPTS);
        }

        @Test
        @DisplayName(
                "a failed deliver() attempt returns false and does NOT write a terminal status (left PENDING for the next sweep pass)")
        void failedDeliverReturnsFalseAndLeavesPending() {
            lenient().when(handler.findExistingDelivery(completedJob)).thenReturn(ExistingDeliveryLookup.absent());
            doThrow(new RuntimeException("GitHub API rate limited"))
                    .when(handler)
                    .deliver(completedJob);

            boolean result = service.recoverStuckDelivery(completedJob, CLAIMED_ATTEMPTS);

            assertThat(result).isFalse();
            verify(agentJobRepository, never()).transitionDeliveryStatusFenced(any(), any(), any(), any(), anyShort());
            verify(agentJobRepository, never()).updateDeliveryStatus(any(), any(), any());
        }

        @Test
        @DisplayName("UNKNOWN (dedup check inconclusive) does NOT call deliver() — leaves PENDING for a later pass")
        void unknownLeavesPendingWithoutPosting() {
            lenient().when(handler.findExistingDelivery(completedJob)).thenReturn(ExistingDeliveryLookup.unknown());

            boolean result = service.recoverStuckDelivery(completedJob, CLAIMED_ATTEMPTS);

            assertThat(result).isFalse();
            verify(handler, never()).deliver(any());
            verify(agentJobRepository, never()).transitionDeliveryStatusFenced(any(), any(), any(), any(), anyShort());
        }

        @Test
        @DisplayName("a handler whose dedup check itself throws is treated as UNKNOWN — does not post")
        void dedupCheckThrowingIsTreatedAsUnknown() {
            lenient().when(handler.findExistingDelivery(completedJob)).thenThrow(new RuntimeException("provider down"));

            boolean result = service.recoverStuckDelivery(completedJob, CLAIMED_ATTEMPTS);

            assertThat(result).isFalse();
            verify(handler, never()).deliver(any());
        }

        @Test
        @DisplayName("a superseded attempt's fenced write matches no row — returns false without clobbering the winner")
        void supersededAttemptWriteIsANoOp() {
            lenient().when(handler.findExistingDelivery(completedJob)).thenReturn(ExistingDeliveryLookup.absent());
            // Fence lost: a later attempt already advanced delivery_attempts past what this call claimed.
            lenient()
                    .when(agentJobRepository.transitionDeliveryStatusFenced(
                            eq(jobId), eq(DeliveryStatus.DELIVERED), any(), any(), eq(CLAIMED_ATTEMPTS)))
                    .thenReturn(0);

            boolean result = service.recoverStuckDelivery(completedJob, CLAIMED_ATTEMPTS);

            assertThat(result).isFalse();
            verify(handler).deliver(completedJob);
        }
    }
}
