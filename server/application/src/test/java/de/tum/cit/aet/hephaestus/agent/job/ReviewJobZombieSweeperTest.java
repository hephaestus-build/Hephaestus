package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.MeasurementVerification;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ReviewJobZombieSweeperTest extends BaseUnitTest {

    @Mock
    private ReviewJobRepository jobRepository;

    @Mock
    private WorkerRegistryRepository workerRegistryRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SimpleMeterRegistry meterRegistry;

    private ReviewJobZombieSweeper sweeper;

    private static final Instant START = Instant.parse("2026-10-27T20:00:00Z");

    private final AtomicReference<Instant> databaseClock = new AtomicReference<>(START);

    private static final AgentProperties AGENT_PROPS = new AgentProperties(
            true, Duration.ofSeconds(1), 5, 5, Duration.ofSeconds(25), Duration.ofDays(14), Duration.ofDays(90), 16);

    @Mock
    private LlmUsageRecorder usageRecorder;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        // Run the TransactionTemplate callback inline so CAS writes execute in tests.
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });
        lenient()
                .doAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Consumer<TransactionStatus> consumer = inv.getArgument(0);
                    consumer.accept(mock(TransactionStatus.class));
                    return null;
                })
                .when(transactionTemplate)
                .executeWithoutResult(any());
        ReviewAttemptUsageService attemptUsage =
                TerminalUsageTestFixtures.attemptUsageOverMocks(jobRepository, usageRecorder);
        sweeper = new ReviewJobZombieSweeper(
                jobRepository,
                workerRegistryRepository,
                objectMapper,
                transactionTemplate,
                attemptUsage,
                meterRegistry,
                new ReviewJobTelemetry(meterRegistry, Tracer.NOOP),
                new ReviewJobRecovery(jobRepository, AGENT_PROPS, objectMapper, attemptUsage, Optional.empty()),
                AGENT_PROPS);
        lenient().when(jobRepository.databaseNow()).thenAnswer(inv -> databaseClock.get());
        // The first lease check after a start reaps nothing; each test's check then follows it, past the quiet time.
        databaseClock.set(START);
        sweeper.recoverOrphanedJobs();
        databaseClock.set(START.plusSeconds(30));
    }

    private ConfigSnapshot admittedSnapshot(int timeoutSeconds) {
        return new ConfigSnapshot(
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
                                null),
                        null,
                        null,
                        timeoutSeconds,
                        false,
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
    }

    private ReviewJob runningJob(UUID id, Instant startedAt, int timeoutSeconds) {
        ReviewJob job = new ReviewJob();
        job.setId(id);
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setStartedAt(startedAt);

        RuntimeJobTestData.initialize(job, workspace(7L).getId());
        job.setConfigSnapshot(admittedSnapshot(timeoutSeconds).toJson(objectMapper));
        return job;
    }

    private ReviewJob orphanedJob(UUID id, Long workspaceId, int attempt) {
        ReviewJob job = runningJob(id, Instant.now().minusSeconds(180), 600);
        RuntimeJobTestData.initialize(job, workspace(workspaceId).getId());
        job.setWorkerId(DEAD_WORKER_ID);
        job.setExecutionStartedAt(Instant.now());
        job.setAttempt(attempt);
        return job;
    }

    private static Workspace workspace(Long id) {
        Workspace workspace = new Workspace();
        workspace.setId(id);
        return workspace;
    }

    private JsonNode snapshotFromTheFuture() {
        ObjectNode node = (ObjectNode) admittedSnapshot(600).toJson(objectMapper);
        return node.put("schemaVersion", ConfigSnapshot.SCHEMA_VERSION + 1);
    }

    private LlmUsageRecorder.LlmUsageSample capturePriced() {
        ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
        verify(usageRecorder).project(eq(7L), sample.capture());
        verify(usageRecorder, never())
                .project(
                        any(),
                        argThat(recorded ->
                                recorded.measurementVerification() == MeasurementVerification.UNVERIFIABLE));
        return sample.getValue();
    }

    private LlmUsageRecorder.LlmUsageSample captureVerifiedWithoutPrice() {
        ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
        verify(usageRecorder).project(eq(7L), sample.capture());
        assertThat(sample.getValue().measurementVerification()).isEqualTo(MeasurementVerification.VERIFIED);
        verify(usageRecorder, never())
                .project(
                        any(),
                        argThat(recorded ->
                                recorded.measurementVerification() == MeasurementVerification.UNVERIFIABLE));
        return sample.getValue();
    }

    private static final String DEAD_WORKER_ID = "dead-replica";

    private static OrphanedJobRef orphan(UUID jobId, Long workspaceId, int attempt) {
        return orphan(jobId, workspaceId, attempt, DEAD_WORKER_ID);
    }

    private static OrphanedJobRef orphan(UUID jobId, Long workspaceId, int attempt, String workerId) {
        return new OrphanedJobRef() {
            @Override
            public UUID getJobId() {
                return jobId;
            }

            @Override
            public Long getWorkspaceId() {
                return workspaceId;
            }

            @Override
            public int getAttempt() {
                return attempt;
            }

            @Override
            public String getWorkerId() {
                return workerId;
            }
        };
    }

    @Nested
    @DisplayName("recoverOrphanedJobs")
    class RecoverOrphaned {

        @Test
        @DisplayName("the first lease check after a start reaps nothing")
        void shouldReapNothingWhenTheLeaseCheckRunsForTheFirstTime() {
            var fresh = new ReviewJobZombieSweeper(
                    jobRepository,
                    workerRegistryRepository,
                    objectMapper,
                    transactionTemplate,
                    TerminalUsageTestFixtures.attemptUsageOverMocks(jobRepository, usageRecorder),
                    meterRegistry,
                    new ReviewJobTelemetry(meterRegistry, Tracer.NOOP),
                    new ReviewJobRecovery(
                            jobRepository,
                            AGENT_PROPS,
                            objectMapper,
                            TerminalUsageTestFixtures.attemptUsageOverMocks(jobRepository, usageRecorder),
                            Optional.empty()),
                    AGENT_PROPS);
            clearInvocations(jobRepository);

            fresh.recoverOrphanedJobs();

            verify(jobRepository, never()).findExpiredLeases(any());
        }

        @Test
        @DisplayName("a lease counts as expired only one renewal interval before the previous check")
        void shouldGraceOneRenewalIntervalWhenItJudgesALease() {
            sweeper.recoverOrphanedJobs();

            verify(jobRepository).findExpiredLeases(START.minus(AGENT_PROPS.heartbeatInterval()));
        }

        @Test
        @DisplayName("after a stall, no lease counts as expired until every worker could renew")
        void shouldReapNothingForOneRenewalIntervalWhenTheChecksStalled() {
            sweeper.recoverOrphanedJobs();
            clearInvocations(jobRepository);
            Instant resumed =
                    START.plusSeconds(30).plus(ReviewJobZombieSweeper.STALL_GAP).plusSeconds(1);
            databaseClock.set(resumed);

            sweeper.recoverOrphanedJobs();
            databaseClock.set(resumed.plusSeconds(20));
            sweeper.recoverOrphanedJobs();
            verify(jobRepository, never()).findExpiredLeases(any());

            databaseClock.set(resumed.plus(AGENT_PROPS.heartbeatInterval()).plusSeconds(1));
            sweeper.recoverOrphanedJobs();
            verify(jobRepository).findExpiredLeases(resumed.plusSeconds(20).minus(AGENT_PROPS.heartbeatInterval()));
        }

        @Test
        @DisplayName("a lease that a heartbeat renewed after the select is not recovered")
        void shouldKeepTheAttemptWhenItsLeaseWasRenewedSinceTheSelect() {
            UUID jobId = UUID.randomUUID();
            when(jobRepository.findExpiredLeases(any())).thenReturn(List.of(orphan(jobId, 7L, 0)));
            ReviewJob renewed = new ReviewJob();
            renewed.setLeasedUntil(START.plusSeconds(330));
            when(jobRepository.lockById(jobId)).thenReturn(Optional.of(renewed));

            sweeper.recoverOrphanedJobs();

            verify(jobRepository, never()).requeueOrphan(any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("requeues (RUNNING → QUEUED) and counts it once the CAS wins")
        void shouldRequeueWithoutUsageWhenTheOrphanMadeNoModelCalls() {
            UUID jobId = UUID.randomUUID();
            when(jobRepository.findExpiredLeases(any())).thenReturn(List.of(orphan(jobId, 7L, 0)));
            when(jobRepository.requeueOrphan(eq(jobId), eq(DEAD_WORKER_ID), eq(AGENT_PROPS.maxRetries()), any()))
                    .thenReturn(1);
            ReviewJob persistedJob = orphanedJob(jobId, 7L, 0);
            when(jobRepository.lockById(jobId)).thenReturn(Optional.of(persistedJob));

            sweeper.recoverOrphanedJobs();

            verify(jobRepository).requeueOrphan(eq(jobId), eq(DEAD_WORKER_ID), eq(AGENT_PROPS.maxRetries()), any());
            assertThat(meterRegistry.counter("agent.job.orphan.requeued").count())
                    .isEqualTo(1d);
            verify(usageRecorder, never()).record(any(), any());
            verify(usageRecorder, never()).project(any(), any());
        }

        @Test
        @DisplayName("jobs without an admission snapshot are recovered as explicitly unpriced")
        void recoversJobWithoutPriceSnapshot() {
            UUID jobId = UUID.randomUUID();
            when(jobRepository.findExpiredLeases(any())).thenReturn(List.of(orphan(jobId, 7L, 0)));
            when(jobRepository.requeueOrphan(eq(jobId), eq(DEAD_WORKER_ID), anyInt(), any()))
                    .thenReturn(1);
            ReviewJob legacyJob = orphanedJob(jobId, 7L, 0);
            ConfigSnapshot snapshot = ConfigSnapshot.fromJson(legacyJob.getConfigSnapshot(), objectMapper);
            legacyJob.setConfigSnapshot(new ConfigSnapshot(
                            snapshot.schemaVersion(),
                            new FrozenModel(
                                    snapshot.model().apiProtocol(),
                                    snapshot.model().baseUrl(),
                                    snapshot.model().upstreamModelId(),
                                    snapshot.model().connectionScope(),
                                    snapshot.model().connectionId(),
                                    snapshot.model().modelId(),
                                    snapshot.model().workspaceId(),
                                    null,
                                    snapshot.model().reasoningEffort(),
                                    null),
                            snapshot.contextWindow(),
                            snapshot.maxOutputTokens(),
                            snapshot.timeoutSeconds(),
                            snapshot.allowInternet(),
                            null)
                    .toJson(objectMapper));
            when(jobRepository.lockById(jobId)).thenReturn(Optional.of(legacyJob));
            when(jobRepository.findLlmUsageById(jobId))
                    .thenReturn(Optional.of(new ReviewJobLlmUsage(1, 40, 10, 0, 0, 0)));

            sweeper.recoverOrphanedJobs();

            ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                    ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(usageRecorder).project(eq(7L), sample.capture());
            assertThat(sample.getValue().measurementVerification()).isEqualTo(MeasurementVerification.VERIFIED);
            assertThat(sample.getValue().totalCalls()).isEqualTo(1);
            assertThat(sample.getValue().price().pricingState()).isEqualTo(UsagePricingState.UNPRICED);
            assertThat(sample.getValue().price().fundingSource()).isEqualTo(UsageFundingSource.INSTANCE);
        }

        @Test
        @DisplayName("does not count a requeue win if the CAS lost the race to another sweeper")
        void skipsWhenRequeueRaced() {
            UUID jobId = UUID.randomUUID();
            when(jobRepository.findExpiredLeases(any())).thenReturn(List.of(orphan(jobId, 7L, 0)));
            when(jobRepository.requeueOrphan(eq(jobId), eq(DEAD_WORKER_ID), eq(AGENT_PROPS.maxRetries()), any()))
                    .thenReturn(0);
            when(jobRepository.lockById(jobId)).thenReturn(Optional.of(orphanedJob(jobId, 7L, 0)));

            sweeper.recoverOrphanedJobs();

            verify(jobRepository).requeueOrphan(eq(jobId), eq(DEAD_WORKER_ID), eq(AGENT_PROPS.maxRetries()), any());
            verify(jobRepository, never()).transitionStatus(any(), any(), any(), any(), any());
            verify(usageRecorder, never())
                    .project(
                            any(),
                            argThat(recorded ->
                                    recorded.measurementVerification() == MeasurementVerification.UNVERIFIABLE));
            assertThat(meterRegistry.counter("agent.job.orphan.requeued").count())
                    .isZero();
        }

        @Test
        @DisplayName("fails (not requeues) an orphan that hit the retry cap")
        void failsOrphanPastRetryCap() {
            UUID jobId = UUID.randomUUID();
            when(jobRepository.findExpiredLeases(any())).thenReturn(List.of(orphan(jobId, 7L, 5)));
            ReviewJob persistedJob = orphanedJob(jobId, 7L, 5);
            when(jobRepository.lockById(jobId)).thenReturn(Optional.of(persistedJob));
            when(jobRepository.transitionStatusOwnedBy(
                            eq(jobId), eq(ReviewJobStatus.FAILED), any(), any(), eq(DEAD_WORKER_ID), eq(5)))
                    .thenReturn(1);

            sweeper.recoverOrphanedJobs();

            verify(jobRepository, never()).requeueOrphan(any(), any(), anyInt(), any());
            verify(jobRepository)
                    .transitionStatusOwnedBy(
                            eq(jobId), eq(ReviewJobStatus.FAILED), any(), any(), eq(DEAD_WORKER_ID), eq(5));
            verify(usageRecorder, never()).record(any(), any());
            verify(usageRecorder, never()).project(any(), any());
        }
    }

    @Nested
    class ReapStaleRunning {

        @Test
        @DisplayName("bills the reaped attempt's proxy-recorded tokens at its frozen price")
        void billsTheReapedAttemptsProxyRecordedTokensAtItsFrozenPrice() {
            UUID jobId = UUID.randomUUID();
            ReviewJob job = runningJob(jobId, Instant.now().minusSeconds(1800), 600);
            job.setExecutionStartedAt(Instant.now().minusSeconds(1790));
            job.setAttempt(2);

            when(jobRepository.findStaleRunningJobs(any())).thenReturn(List.of(job));
            when(jobRepository.lockById(jobId)).thenReturn(Optional.of(job));
            when(jobRepository.transitionStatus(any(), any(), any(), any(), any()))
                    .thenReturn(1);
            when(jobRepository.findLlmUsageById(jobId))
                    .thenReturn(Optional.of(new ReviewJobLlmUsage(3, 900, 400, 50, 120, 0)));
            clearInvocations(usageRecorder);

            sweeper.reapStaleRunningJobs();

            verify(jobRepository)
                    .transitionStatus(
                            eq(jobId),
                            eq(ReviewJobStatus.TIMED_OUT),
                            any(),
                            any(),
                            eq(Set.of(ReviewJobStatus.RUNNING)));
            LlmUsageRecorder.LlmUsageSample sample = capturePriced();
            assertThat(sample.sourceId()).isEqualTo(jobId);
            assertThat(sample.sourceAttempt()).isEqualTo(2);
            assertThat(sample.model()).isEqualTo("test-model");
            assertThat(sample.inputTokens()).isEqualTo(900L);
            assertThat(sample.outputTokens()).isEqualTo(400L);
            assertThat(sample.reasoningTokens()).isEqualTo(50L);
            assertThat(sample.cacheReadTokens()).isEqualTo(120L);
            assertThat(sample.totalCalls()).isEqualTo(3);
            assertThat(sample.price().pricingState()).isEqualTo(UsagePricingState.PRICED);
            assertThat(sample.price().fundingSource()).isEqualTo(UsageFundingSource.INSTANCE);
            assertThat(meterRegistry.counter("agent.job.zombie.reaped").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a job that never started executing is reaped without any ledger event")
        void shouldReapAJobStillInPreparationWithoutAttributingUsage() {
            UUID jobId = UUID.randomUUID();
            ReviewJob job = runningJob(jobId, Instant.now().minusSeconds(1800), 600);

            when(jobRepository.findStaleRunningJobs(any())).thenReturn(List.of(job));
            when(jobRepository.lockById(jobId)).thenReturn(Optional.of(job));
            when(jobRepository.transitionStatus(any(), any(), any(), any(), any()))
                    .thenReturn(1);
            clearInvocations(usageRecorder);

            sweeper.reapStaleRunningJobs();

            verify(jobRepository)
                    .transitionStatus(
                            eq(jobId),
                            eq(ReviewJobStatus.TIMED_OUT),
                            any(),
                            any(),
                            eq(Set.of(ReviewJobStatus.RUNNING)));
            // It was still preparing, so there is nothing to bill and nothing to flag as unverifiable
            // — an UNPRICED row here would make the workspace's month read unverifiable for free.
            verifyNoInteractions(usageRecorder);
            assertThat(meterRegistry.counter("agent.job.zombie.reaped").count()).isEqualTo(1.0);
            assertThat(meterRegistry.counter("agent.job.snapshot.unreadable").count())
                    .isZero();
        }

        @Test
        @DisplayName("a job inside its own timeout is left RUNNING")
        void shouldSkipRunningJobsNotYetStale() {
            UUID jobId = UUID.randomUUID();
            ReviewJob job = runningJob(jobId, Instant.now().minusSeconds(300), 600);

            when(jobRepository.findStaleRunningJobs(any())).thenReturn(List.of(job));
            when(jobRepository.lockById(jobId)).thenReturn(Optional.of(job));

            sweeper.reapStaleRunningJobs();

            assertThat(job.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
            assertThat(meterRegistry.counter("agent.job.zombie.reaped").count()).isZero();
            verifyNoInteractions(usageRecorder);
        }

        @Test
        void shouldNotReapAnAttemptDuringResultUploadGrace() {
            UUID jobId = UUID.randomUUID();
            ReviewJob job = runningJob(jobId, Instant.now().minusSeconds(1200), 600);
            job.setExecutionStartedAt(Instant.now().minusSeconds(1190));
            when(jobRepository.findStaleRunningJobs(any())).thenReturn(List.of(job));
            when(jobRepository.lockById(jobId)).thenReturn(Optional.of(job));

            sweeper.reapStaleRunningJobs();

            verify(jobRepository, never()).transitionStatus(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a snapshot this server cannot read still terminalises the job, billed UNPRICED")
        void unreadableSnapshotStillTerminalisesTheJob() {
            UUID jobId = UUID.randomUUID();
            ReviewJob job = runningJob(jobId, Instant.now().minusSeconds(1800), 600);
            job.setExecutionStartedAt(Instant.now().minusSeconds(1790));
            job.setConfigSnapshot(snapshotFromTheFuture());

            when(jobRepository.findStaleRunningJobs(any())).thenReturn(List.of(job));
            when(jobRepository.lockById(jobId)).thenReturn(Optional.of(job));
            when(jobRepository.transitionStatus(any(), any(), any(), any(), any()))
                    .thenReturn(1);
            when(jobRepository.findLlmUsageById(jobId))
                    .thenReturn(Optional.of(new ReviewJobLlmUsage(2, 500, 100, 0, 0, 0)));
            clearInvocations(usageRecorder);

            sweeper.reapStaleRunningJobs();

            verify(jobRepository)
                    .transitionStatus(
                            eq(jobId),
                            eq(ReviewJobStatus.TIMED_OUT),
                            any(),
                            any(),
                            eq(Set.of(ReviewJobStatus.RUNNING)));
            assertThat(meterRegistry.counter("agent.job.zombie.reaped").count()).isEqualTo(1.0);
            LlmUsageRecorder.LlmUsageSample sample = captureVerifiedWithoutPrice();
            assertThat(sample.price().pricingState()).isEqualTo(UsagePricingState.UNPRICED);
            assertThat(sample.price().fundingSource()).isEqualTo(UsageFundingSource.INSTANCE);
            assertThat(sample.price().per1mInputUsd()).isNull();
            assertThat(sample.model()).isNull();
            assertThat(sample.inputTokens()).isEqualTo(500L);
            assertThat(sample.totalCalls()).isEqualTo(2);
            assertThat(meterRegistry.counter("agent.job.snapshot.unreadable").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("an unreadable snapshot falls back to the default timeout rather than skipping the job")
        void unreadableSnapshotFallsBackToTheDefaultTimeout() {
            UUID jobId = UUID.randomUUID();
            ReviewJob job = runningJob(jobId, Instant.now().minusSeconds(1800), 600);
            job.setConfigSnapshot(snapshotFromTheFuture());

            when(jobRepository.findStaleRunningJobs(any())).thenReturn(List.of(job));
            when(jobRepository.lockById(jobId)).thenReturn(Optional.of(job));
            when(jobRepository.transitionStatus(any(), any(), any(), any(), any()))
                    .thenReturn(1);

            sweeper.reapStaleRunningJobs();

            assertThat(meterRegistry.counter("agent.job.zombie.reaped").count()).isEqualTo(1.0);
        }
    }
}
