package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxManager;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.MeasurementVerification;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.math.BigDecimal;
import java.time.Instant;
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
import tools.jackson.databind.json.JsonMapper;

class ReviewJobLifecycleServiceTest extends BaseUnitTest {

    @Mock
    private ReviewJobRepository jobRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private SandboxManager sandboxManager;

    @Mock
    private LlmUsageRecorder usageRecorder;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private ReviewJobLifecycleService service;

    private Workspace workspace;

    @BeforeEach
    void setUp() {
        service = new ReviewJobLifecycleService(
                jobRepository,
                transactionTemplate,
                sandboxManager,
                TerminalUsageTestFixtures.attemptUsageOverMocks(jobRepository, usageRecorder),
                objectMapper,
                new ReviewJobTelemetry(new SimpleMeterRegistry(), Tracer.NOOP));

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

    private ReviewJob createJobWithStatus(ReviewJobStatus status) {
        ReviewJob job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());
        job.setStatus(status);

        ConfigSnapshot snapshot = new ConfigSnapshot(
                        ConfigSnapshot.SCHEMA_VERSION,
                        new FrozenModel(
                                LlmApiProtocol.OPENAI_COMPLETIONS,
                                "https://api.openai.com/v1",
                                "test-model",
                                UsageFundingSource.INSTANCE,
                                1L,
                                1L,
                                null,
                                null,
                                null,
                                null,
                                false),
                        null,
                        null,
                        600,
                        null)
                .withPriceSnapshot(new LlmPriceSnapshot(
                        UsageFundingSource.INSTANCE,
                        UsagePricingState.PRICED,
                        1L,
                        null,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE));
        job.setConfigSnapshot(snapshot.toJson(objectMapper));
        job.prePersist();
        return job;
    }

    @Nested
    class Cancel {

        @Test
        void shouldBeIdempotentForCancelledJob() {
            ReviewJob job = createJobWithStatus(ReviewJobStatus.CANCELLED);
            UUID jobId = job.getId();

            when(jobRepository.findByIdAndWorkspaceId(jobId, 1L)).thenReturn(Optional.of(job));

            ReviewJob result = service.cancel(1L, jobId);

            assertThat(result.getStatus()).isEqualTo(ReviewJobStatus.CANCELLED);
            verify(jobRepository, never()).transitionToCancelled(any(), any(), any(), any(), any());
        }

        @ParameterizedTest
        @EnumSource(
                value = ReviewJobStatus.class,
                names = {"COMPLETED", "FAILED", "TIMED_OUT"})
        void shouldThrow409ForAnAlreadyTerminalJob(ReviewJobStatus terminalStatus) {
            ReviewJob job = createJobWithStatus(terminalStatus);
            UUID jobId = job.getId();

            when(jobRepository.findByIdAndWorkspaceId(jobId, 1L)).thenReturn(Optional.of(job));

            assertThatThrownBy(() -> service.cancel(1L, jobId))
                    .isInstanceOf(ReviewJobStateConflictException.class)
                    .hasMessage("This review already finished. You cannot cancel it.");
        }

        @Test
        void shouldThrow404ForNonExistentJob() {
            UUID jobId = UUID.randomUUID();
            when(jobRepository.findByIdAndWorkspaceId(jobId, 1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.cancel(1L, jobId)).isInstanceOf(EntityNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Unpriced usage ledger write on user-cancel")
    class UnverifiableUsageLedger {

        @Test
        void shouldWriteNoUsageWhenACancelledExecutionMadeNoModelCall() {
            ReviewJob job = createJobWithStatus(ReviewJobStatus.RUNNING);
            job.setLeaseId(UUID.randomUUID());
            job.setExecutionStartedAt(Instant.now());
            UUID jobId = job.getId();

            when(jobRepository.findByIdAndWorkspaceId(jobId, 1L)).thenReturn(Optional.of(job));
            when(jobRepository.transitionToCancelled(eq(jobId), any(), any(), any(), any()))
                    .thenReturn(1);
            ReviewJob cancelledJob = createJobWithStatus(ReviewJobStatus.CANCELLED);
            cancelledJob.setId(jobId);
            cancelledJob.setLeaseId(UUID.randomUUID());
            cancelledJob.setExecutionStartedAt(job.getExecutionStartedAt());
            when(jobRepository.findByIdAndWorkspaceId(jobId, 1L))
                    .thenReturn(Optional.of(job))
                    .thenReturn(Optional.of(cancelledJob));

            service.cancel(1L, jobId);

            verify(usageRecorder, never()).record(any(), any());
            verify(usageRecorder, never()).project(any(), any());
        }

        @Test
        void cancellationReadsCommittedProxyCountersInsteadOfTheEarlierEntity() {
            ReviewJob running = createJobWithStatus(ReviewJobStatus.RUNNING);
            running.setExecutionStartedAt(Instant.now());
            UUID id = running.getId();
            ReviewJob cancelled = createJobWithStatus(ReviewJobStatus.CANCELLED);
            cancelled.setId(id);
            cancelled.setExecutionStartedAt(running.getExecutionStartedAt());
            when(jobRepository.findByIdAndWorkspaceId(id, 1L))
                    .thenReturn(Optional.of(running))
                    .thenReturn(Optional.of(cancelled));
            when(jobRepository.transitionToCancelled(eq(id), any(), any(), any(), any()))
                    .thenReturn(1);
            when(jobRepository.findLlmUsageById(id))
                    .thenReturn(Optional.of(new ReviewJobLlmUsage(4, 900, 600, 30, 100, 0)));

            service.cancel(1L, id);

            var captured = ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(usageRecorder).project(eq(1L), captured.capture());
            assertThat(captured.getValue().measurementVerification()).isEqualTo(MeasurementVerification.VERIFIED);
            assertThat(captured.getValue().inputTokens()).isEqualTo(900);
            assertThat(captured.getValue().outputTokens()).isEqualTo(600);
            assertThat(captured.getValue().totalCalls()).isEqualTo(4);
        }

        @Test
        void shouldPreserveCancellationWhenTheFrozenModelHasNoAdmissionPrice() {
            ReviewJob job = createJobWithStatus(ReviewJobStatus.RUNNING);
            job.setLeaseId(UUID.randomUUID());
            job.setExecutionStartedAt(Instant.now());
            ConfigSnapshot unpricedSnapshot = ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper)
                    .withPriceSnapshot(null);
            job.setConfigSnapshot(unpricedSnapshot.toJson(objectMapper));
            UUID jobId = job.getId();

            when(jobRepository.findByIdAndWorkspaceId(jobId, 1L)).thenReturn(Optional.of(job));
            when(jobRepository.transitionToCancelled(eq(jobId), any(), any(), any(), any()))
                    .thenReturn(1);
            ReviewJob cancelledJob = createJobWithStatus(ReviewJobStatus.CANCELLED);
            cancelledJob.setId(jobId);
            cancelledJob.setLeaseId(UUID.randomUUID());
            cancelledJob.setExecutionStartedAt(job.getExecutionStartedAt());
            cancelledJob.setConfigSnapshot(unpricedSnapshot.toJson(objectMapper));
            when(jobRepository.findByIdAndWorkspaceId(jobId, 1L))
                    .thenReturn(Optional.of(job))
                    .thenReturn(Optional.of(cancelledJob));

            when(jobRepository.findLlmUsageById(jobId))
                    .thenReturn(Optional.of(new ReviewJobLlmUsage(1, 40, 10, 0, 0, 0)));

            ReviewJob result = service.cancel(1L, jobId);

            assertThat(result.getStatus()).isEqualTo(ReviewJobStatus.CANCELLED);
            ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                    ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(usageRecorder).project(eq(1L), sample.capture());
            assertThat(sample.getValue().measurementVerification()).isEqualTo(MeasurementVerification.VERIFIED);
            assertThat(sample.getValue().totalCalls()).isEqualTo(1);
            assertThat(sample.getValue().price().fundingSource()).isEqualTo(UsageFundingSource.INSTANCE);
            assertThat(sample.getValue().price().pricingState()).isEqualTo(UsagePricingState.UNPRICED);
        }

        @Test
        void cancellingAJobThatNeverStarted_neverTouchesTheLedger() {
            ReviewJob job = createJobWithStatus(ReviewJobStatus.QUEUED);
            UUID jobId = job.getId();

            when(jobRepository.findByIdAndWorkspaceId(jobId, 1L)).thenReturn(Optional.of(job));
            when(jobRepository.transitionToCancelled(eq(jobId), any(), any(), any(), any()))
                    .thenReturn(1);
            ReviewJob cancelledJob = createJobWithStatus(ReviewJobStatus.CANCELLED);
            cancelledJob.setId(jobId);
            when(jobRepository.findByIdAndWorkspaceId(jobId, 1L))
                    .thenReturn(Optional.of(job))
                    .thenReturn(Optional.of(cancelledJob));

            service.cancel(1L, jobId);

            verify(usageRecorder, never())
                    .project(any(), argThat(r -> r.measurementVerification() == MeasurementVerification.UNVERIFIABLE));
        }

        @Test
        void cancellingAClaimedJobStillInPreparation_neverTouchesTheLedger() {
            ReviewJob job = createJobWithStatus(ReviewJobStatus.RUNNING);
            job.setLeaseId(UUID.randomUUID());
            UUID jobId = job.getId();

            when(jobRepository.findByIdAndWorkspaceId(jobId, 1L)).thenReturn(Optional.of(job));
            when(jobRepository.transitionToCancelled(eq(jobId), any(), any(), any(), any()))
                    .thenReturn(1);
            ReviewJob cancelledJob = createJobWithStatus(ReviewJobStatus.CANCELLED);
            cancelledJob.setId(jobId);
            cancelledJob.setLeaseId(UUID.randomUUID());
            when(jobRepository.findByIdAndWorkspaceId(jobId, 1L))
                    .thenReturn(Optional.of(job))
                    .thenReturn(Optional.of(cancelledJob));

            service.cancel(1L, jobId);

            verify(usageRecorder, never())
                    .project(any(), argThat(r -> r.measurementVerification() == MeasurementVerification.UNVERIFIABLE));
        }

        @Test
        void idempotentCancelOfAnAlreadyCancelledStartedJob_doesNotWriteOutsideWinningTransaction() {
            ReviewJob job = createJobWithStatus(ReviewJobStatus.CANCELLED);
            job.setLeaseId(UUID.randomUUID());
            UUID jobId = job.getId();

            when(jobRepository.findByIdAndWorkspaceId(jobId, 1L)).thenReturn(Optional.of(job));

            service.cancel(1L, jobId);

            verify(usageRecorder, never())
                    .project(any(), argThat(r -> r.measurementVerification() == MeasurementVerification.UNVERIFIABLE));
            verify(jobRepository, never()).transitionToCancelled(any(), any(), any(), any(), any());
        }
    }

    @Test
    void shouldRecordProxySpendWhenCancellingARunningAttempt() {
        ReviewJob running = createJobWithStatus(ReviewJobStatus.RUNNING);
        running.setExecutionStartedAt(Instant.now());
        ReviewJob cancelled = createJobWithStatus(ReviewJobStatus.CANCELLED);
        cancelled.setId(running.getId());
        cancelled.setExecutionStartedAt(running.getExecutionStartedAt());
        when(jobRepository.findByIdAndWorkspaceId(running.getId(), 1L))
                .thenReturn(Optional.of(running))
                .thenReturn(Optional.of(cancelled));
        when(jobRepository.transitionToCancelled(eq(running.getId()), any(), any(), any(), any()))
                .thenReturn(1);
        when(jobRepository.findLlmUsageById(running.getId()))
                .thenReturn(Optional.of(new ReviewJobLlmUsage(2, 120, 30, 4, 10, 5)));

        assertThat(service.cancel(1L, running.getId()).getStatus()).isEqualTo(ReviewJobStatus.CANCELLED);

        ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
        verify(usageRecorder).project(eq(1L), sample.capture());
        assertThat(sample.getValue().inputTokens()).isEqualTo(120);
        assertThat(sample.getValue().outputTokens()).isEqualTo(30);
        assertThat(sample.getValue().sourceId()).isEqualTo(running.getId());
    }
}
