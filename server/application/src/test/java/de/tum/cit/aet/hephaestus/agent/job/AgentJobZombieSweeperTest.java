package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.PricingState;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class AgentJobZombieSweeperTest extends BaseUnitTest {

    @Mock
    private AgentJobRepository jobRepository;

    @Mock
    private WorkerRegistryRepository workerRegistryRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SimpleMeterRegistry meterRegistry;

    private AgentJobZombieSweeper sweeper;

    private static final AgentProperties AGENT_PROPS = new AgentProperties(
            true,
            java.time.Duration.ofSeconds(1),
            5,
            5,
            java.time.Duration.ofSeconds(25),
            java.time.Duration.ofDays(14),
            java.time.Duration.ofDays(90));

    @Mock
    private AgentJobLifecycleService lifecycleService;

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
                    java.util.function.Consumer<TransactionStatus> consumer = inv.getArgument(0);
                    consumer.accept(mock(TransactionStatus.class));
                    return null;
                })
                .when(transactionTemplate)
                .executeWithoutResult(any());
        sweeper = new AgentJobZombieSweeper(
                jobRepository,
                workerRegistryRepository,
                AGENT_PROPS,
                objectMapper,
                transactionTemplate,
                lifecycleService,
                usageRecorder,
                meterRegistry,
                new AgentJobTelemetry(meterRegistry));
    }

    private ConfigSnapshot admittedSnapshot(int timeoutSeconds) {
        return new ConfigSnapshot(
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
                        timeoutSeconds,
                        false,
                        null)
                .withPriceSnapshot(new LlmPriceSnapshot(
                        FundingSource.INSTANCE, PricingState.NO_CHARGE, null, null, null, null, null, null));
    }

    private AgentJob runningJob(UUID id, Instant startedAt, int timeoutSeconds) {
        AgentJob job = new AgentJob();
        job.setId(id);
        job.setStatus(AgentJobStatus.RUNNING);
        job.setStartedAt(startedAt);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setWorkspace(workspace(7L));
        job.setConfigSnapshot(admittedSnapshot(timeoutSeconds).toJson(objectMapper));
        return job;
    }

    private AgentJob orphanedJob(UUID id, Long workspaceId, int retryCount) {
        AgentJob job = runningJob(id, Instant.now().minusSeconds(180), 600);
        job.setWorkspace(workspace(workspaceId));
        job.setWorkerId(DEAD_WORKER_ID);
        job.setExecutionStartedAt(Instant.now());
        job.setRetryCount(retryCount);
        return job;
    }

    private static Workspace workspace(Long id) {
        Workspace workspace = new Workspace();
        workspace.setId(id);
        return workspace;
    }

    private tools.jackson.databind.JsonNode snapshotFromTheFuture() {
        tools.jackson.databind.node.ObjectNode node =
                (tools.jackson.databind.node.ObjectNode) admittedSnapshot(600).toJson(objectMapper);
        return node.put("schemaVersion", ConfigSnapshot.SCHEMA_VERSION + 1);
    }

    private LlmUsageRecorder.LlmUsageSample capturePriced() {
        ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
        verify(usageRecorder).record(eq(7L), sample.capture());
        verify(usageRecorder, never()).recordUnverifiable(any(), any());
        return sample.getValue();
    }

    private LlmUsageRecorder.LlmUsageSample captureUnverifiable() {
        ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
        verify(usageRecorder).recordUnverifiable(eq(7L), sample.capture());
        verify(usageRecorder, never()).record(any(), any());
        return sample.getValue();
    }

    private static final String DEAD_WORKER_ID = "dead-replica";

    private static OrphanedJobRef orphan(UUID jobId, Long workspaceId, int retryCount) {
        return orphan(jobId, workspaceId, retryCount, DEAD_WORKER_ID);
    }

    private static OrphanedJobRef orphan(UUID jobId, Long workspaceId, int retryCount, String workerId) {
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
            public int getRetryCount() {
                return retryCount;
            }

            @Override
            public String getWorkerId() {
                return workerId;
            }
        };
    }

    @Nested
    @DisplayName("recoverOrphanedJobs (#1138)")
    class RecoverOrphaned {

        @Test
        @DisplayName("requeues (RUNNING → QUEUED) and counts it once the CAS wins")
        void requeuesOrphanedJob() {
            UUID jobId = UUID.randomUUID();
            when(jobRepository.findOrphanedRunningJobs(any(), ArgumentMatchers.anyLong()))
                    .thenReturn(List.of(orphan(jobId, 7L, 0)));
            when(jobRepository.requeueOrphan(
                            eq(jobId), eq(DEAD_WORKER_ID), eq(AGENT_PROPS.maxRetries()), any(), any(), any()))
                    .thenReturn(1);
            AgentJob persistedJob = orphanedJob(jobId, 7L, 0);
            when(jobRepository.findByIdWithWorkspaceForUpdate(jobId)).thenReturn(java.util.Optional.of(persistedJob));

            sweeper.recoverOrphanedJobs();

            verify(jobRepository)
                    .requeueOrphan(eq(jobId), eq(DEAD_WORKER_ID), eq(AGENT_PROPS.maxRetries()), any(), any(), any());
            assertThat(meterRegistry.counter("agent.job.orphan.requeued").count())
                    .isEqualTo(1d);
            ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                    ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(usageRecorder).recordUnverifiable(eq(7L), sample.capture());
            assertThat(sample.getValue().sourceId()).isEqualTo(jobId);
            assertThat(sample.getValue().sourceAttempt()).isZero();
        }

        @Test
        @DisplayName("legacy jobs without an admission snapshot are recovered as explicitly unpriced")
        void recoversLegacyJobWithoutPriceSnapshot() {
            UUID jobId = UUID.randomUUID();
            when(jobRepository.findOrphanedRunningJobs(any(), ArgumentMatchers.anyLong()))
                    .thenReturn(List.of(orphan(jobId, 7L, 0)));
            when(jobRepository.requeueOrphan(eq(jobId), eq(DEAD_WORKER_ID), anyInt(), any(), any(), any()))
                    .thenReturn(1);
            AgentJob legacyJob = orphanedJob(jobId, 7L, 0);
            ConfigSnapshot snapshot = ConfigSnapshot.fromJson(legacyJob.getConfigSnapshot(), objectMapper);
            legacyJob.setConfigSnapshot(new ConfigSnapshot(
                            snapshot.schemaVersion(),
                            snapshot.apiProtocol(),
                            snapshot.baseUrl(),
                            snapshot.upstreamModelId(),
                            snapshot.modelVersion(),
                            snapshot.contextWindow(),
                            snapshot.maxOutputTokens(),
                            snapshot.supportsReasoning(),
                            snapshot.connectionScope(),
                            snapshot.connectionId(),
                            snapshot.modelId(),
                            snapshot.workspaceId(),
                            snapshot.timeoutSeconds(),
                            snapshot.allowInternet(),
                            null // the point of the fixture: an orphan frozen before admission pricing existed
                            )
                    .toJson(objectMapper));
            when(jobRepository.findByIdWithWorkspaceForUpdate(jobId)).thenReturn(java.util.Optional.of(legacyJob));

            sweeper.recoverOrphanedJobs();

            ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                    ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(usageRecorder).recordUnverifiable(eq(7L), sample.capture());
            assertThat(sample.getValue().price().pricingState()).isEqualTo(PricingState.UNPRICED);
            assertThat(sample.getValue().price().fundingSource()).isEqualTo(FundingSource.INSTANCE);
        }

        @Test
        @DisplayName("does not count a requeue win if the CAS lost the race to another sweeper")
        void skipsWhenRequeueRaced() {
            UUID jobId = UUID.randomUUID();
            when(jobRepository.findOrphanedRunningJobs(any(), ArgumentMatchers.anyLong()))
                    .thenReturn(List.of(orphan(jobId, 7L, 0)));
            when(jobRepository.requeueOrphan(
                            eq(jobId), eq(DEAD_WORKER_ID), eq(AGENT_PROPS.maxRetries()), any(), any(), any()))
                    .thenReturn(0); // another replica won
            when(jobRepository.findByIdWithWorkspaceForUpdate(jobId))
                    .thenReturn(java.util.Optional.of(orphanedJob(jobId, 7L, 0)));

            sweeper.recoverOrphanedJobs();

            verify(jobRepository)
                    .requeueOrphan(eq(jobId), eq(DEAD_WORKER_ID), eq(AGENT_PROPS.maxRetries()), any(), any(), any());
            verify(jobRepository, never()).transitionStatus(any(), any(), any(), any(), any());
            verify(usageRecorder, never()).recordUnverifiable(any(), any());
            assertThat(meterRegistry.counter("agent.job.orphan.requeued").count())
                    .isZero();
        }

        @Test
        @DisplayName("fails (not requeues) an orphan that hit the retry cap")
        void failsOrphanPastRetryCap() {
            UUID jobId = UUID.randomUUID();
            when(jobRepository.findOrphanedRunningJobs(any(), ArgumentMatchers.anyLong()))
                    .thenReturn(List.of(orphan(jobId, 7L, 5))); // retryCount == maxRetries
            AgentJob persistedJob = orphanedJob(jobId, 7L, 5);
            when(jobRepository.findByIdWithWorkspaceForUpdate(jobId)).thenReturn(java.util.Optional.of(persistedJob));
            when(jobRepository.transitionStatus(
                            eq(jobId), eq(AgentJobStatus.FAILED), any(), any(), eq(Set.of(AgentJobStatus.RUNNING))))
                    .thenReturn(1);

            sweeper.recoverOrphanedJobs();

            verify(jobRepository, never()).requeueOrphan(any(), any(), anyInt(), any(), any(), any());
            verify(jobRepository)
                    .transitionStatus(
                            eq(jobId), eq(AgentJobStatus.FAILED), any(), any(), eq(Set.of(AgentJobStatus.RUNNING)));
            ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                    ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
            verify(usageRecorder).recordUnverifiable(eq(7L), sample.capture());
            assertThat(sample.getValue().sourceId()).isEqualTo(jobId);
            assertThat(sample.getValue().sourceAttempt()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("recoverStuckDeliveries")
    class RecoverStuckDeliveries {

        private AgentJob stuckJob(short attempts) {
            AgentJob job = new AgentJob();
            job.setId(UUID.randomUUID());
            job.setStatus(AgentJobStatus.COMPLETED);
            job.setDeliveryStatus(DeliveryStatus.PENDING);
            job.setDeliveryAttempts(attempts);
            return job;
        }

        /**
         * The sweep decides from three columns and reads the job itself only for the attempt it wins,
         * so a candidate is staged as the row the query returns plus the job behind it.
         */
        private void stagedAsStuck(AgentJob job) {
            when(jobRepository.findStuckPendingDeliveries(any(), any())).thenReturn(List.of(rowFor(job)));
            lenient()
                    .when(jobRepository.findDeliveryRecoveryCandidate(job.getId()))
                    .thenReturn(Optional.of(job));
        }

        private AgentJobRepository.StuckDeliveryRow rowFor(AgentJob job) {
            return new AgentJobRepository.StuckDeliveryRow() {
                @Override
                public UUID getId() {
                    return job.getId();
                }

                @Override
                public short getDeliveryAttempts() {
                    return job.getDeliveryAttempts();
                }

                @Override
                public @Nullable String getDeliveryCommentId() {
                    return job.getDeliveryCommentId();
                }
            };
        }

        @Test
        @DisplayName("claims the attempt CAS, delegates to the lifecycle service, and counts a successful recovery")
        void claimsAndDelegatesOnSuccess() {
            AgentJob job = stuckJob((short) 0);
            stagedAsStuck(job);
            when(jobRepository.claimDeliveryRecoveryAttempt(job.getId(), (short) 0))
                    .thenReturn(1);
            when(lifecycleService.recoverStuckDelivery(job, (short) 1)).thenReturn(true);

            sweeper.recoverStuckDeliveries();

            verify(jobRepository).claimDeliveryRecoveryAttempt(job.getId(), (short) 0);
            // The CAS claimed attempts 0 -> 1; the post-increment value (1) is this attempt's fence token.
            verify(lifecycleService).recoverStuckDelivery(job, (short) 1);
            assertThat(meterRegistry.counter("agent.job.delivery.recovered").count())
                    .isEqualTo(1d);
        }

        @Test
        @DisplayName(
                "a lost attempt-CAS (a concurrent sweeper replica already claimed it) skips the delivery attempt entirely")
        void skipsWhenAttemptCasLost() {
            AgentJob job = stuckJob((short) 0);
            stagedAsStuck(job);
            when(jobRepository.claimDeliveryRecoveryAttempt(job.getId(), (short) 0))
                    .thenReturn(0);

            sweeper.recoverStuckDeliveries();

            verify(lifecycleService, never()).recoverStuckDelivery(any(), org.mockito.ArgumentMatchers.anyShort());
            assertThat(meterRegistry.counter("agent.job.delivery.recovered").count())
                    .isZero();
        }

        @Test
        @DisplayName("a delivery attempt that itself fails is not counted as recovered")
        void failedAttemptIsNotCounted() {
            AgentJob job = stuckJob((short) 1);
            stagedAsStuck(job);
            when(jobRepository.claimDeliveryRecoveryAttempt(job.getId(), (short) 1))
                    .thenReturn(1);
            when(lifecycleService.recoverStuckDelivery(job, (short) 2)).thenReturn(false);

            sweeper.recoverStuckDeliveries();

            assertThat(meterRegistry.counter("agent.job.delivery.recovered").count())
                    .isZero();
        }

        @Test
        @DisplayName(
                "attempts already at the cap: marks FAILED directly, without claiming another attempt or calling the lifecycle service")
        void exhaustedAttemptsMarksFailedDirectlyKeepingTheCommentId() {
            AgentJob job = stuckJob((short) AgentJobZombieSweeper.MAX_DELIVERY_RECOVERY_ATTEMPTS);
            job.setDeliveryCommentId("comment-1");
            stagedAsStuck(job);

            sweeper.recoverStuckDeliveries();

            ArgumentCaptor<DeliveryStatus> status = ArgumentCaptor.forClass(DeliveryStatus.class);
            verify(jobRepository).updateDeliveryStatus(eq(job.getId()), status.capture(), eq("comment-1"));
            assertThat(status.getValue()).isEqualTo(DeliveryStatus.FAILED);
            // Giving up must not also burn another attempt, or delivery_attempts would climb past the
            // cap forever on a row nobody is retrying any more — and must not re-enter delivery, which
            // is what the row was given up on.
            verify(jobRepository, never()).claimDeliveryRecoveryAttempt(any(), org.mockito.ArgumentMatchers.anyShort());
            verify(lifecycleService, never()).recoverStuckDelivery(any(), org.mockito.ArgumentMatchers.anyShort());
            assertThat(meterRegistry.counter("agent.job.delivery.recovered").count())
                    .isZero();
        }
    }

    @Nested
    class ReapStaleRunning {

        @Test
        @DisplayName("bills the reaped attempt's proxy-recorded tokens at its frozen price")
        void billsTheReapedAttemptsProxyRecordedTokensAtItsFrozenPrice() {
            UUID jobId = UUID.randomUUID();
            // Started 20 minutes ago with 600s (10min) timeout + 5min buffer = 15min
            // 20 min > 15 min → stale
            AgentJob job = runningJob(jobId, Instant.now().minusSeconds(1200), 600);
            job.setExecutionStartedAt(Instant.now().minusSeconds(1190));
            job.setRetryCount(2);

            when(jobRepository.findStaleRunningJobs(any())).thenReturn(List.of(job));
            when(jobRepository.findByIdWithWorkspaceForUpdate(jobId)).thenReturn(java.util.Optional.of(job));
            when(jobRepository.transitionStatus(any(), any(), any(), any(), any()))
                    .thenReturn(1);
            when(jobRepository.findLlmUsageById(jobId))
                    .thenReturn(java.util.Optional.of(new AgentJobLlmUsage(3, 900, 400, 50, 120, 0)));
            org.mockito.Mockito.clearInvocations(usageRecorder);

            sweeper.reapStaleRunningJobs();

            verify(jobRepository)
                    .transitionStatus(
                            eq(jobId), eq(AgentJobStatus.TIMED_OUT), any(), any(), eq(Set.of(AgentJobStatus.RUNNING)));
            LlmUsageRecorder.LlmUsageSample sample = capturePriced();
            assertThat(sample.sourceId()).isEqualTo(jobId);
            assertThat(sample.sourceAttempt()).isEqualTo(2);
            assertThat(sample.model()).isEqualTo("test-model");
            assertThat(sample.inputTokens()).isEqualTo(900L);
            assertThat(sample.outputTokens()).isEqualTo(400L);
            assertThat(sample.reasoningTokens()).isEqualTo(50L);
            assertThat(sample.cacheReadTokens()).isEqualTo(120L);
            assertThat(sample.totalCalls()).isEqualTo(3);
            assertThat(sample.price().pricingState()).isEqualTo(PricingState.NO_CHARGE);
            assertThat(sample.price().fundingSource()).isEqualTo(FundingSource.INSTANCE);
            assertThat(meterRegistry.counter("agent.job.zombie.reaped").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a job that never started executing is reaped without any ledger event")
        void shouldReapAJobStillInPreparationWithoutAttributingUsage() {
            UUID jobId = UUID.randomUUID();
            AgentJob job = runningJob(jobId, Instant.now().minusSeconds(1200), 600);

            when(jobRepository.findStaleRunningJobs(any())).thenReturn(List.of(job));
            when(jobRepository.findByIdWithWorkspaceForUpdate(jobId)).thenReturn(java.util.Optional.of(job));
            when(jobRepository.transitionStatus(any(), any(), any(), any(), any()))
                    .thenReturn(1);
            org.mockito.Mockito.clearInvocations(usageRecorder);

            sweeper.reapStaleRunningJobs();

            verify(jobRepository)
                    .transitionStatus(
                            eq(jobId), eq(AgentJobStatus.TIMED_OUT), any(), any(), eq(Set.of(AgentJobStatus.RUNNING)));
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
            // Started 5 minutes ago with 600s (10min) timeout + 5min buffer = 15min
            // 5 min < 15 min → not stale yet
            AgentJob job = runningJob(jobId, Instant.now().minusSeconds(300), 600);

            when(jobRepository.findStaleRunningJobs(any())).thenReturn(List.of(job));
            when(jobRepository.findByIdWithWorkspaceForUpdate(jobId)).thenReturn(java.util.Optional.of(job));

            sweeper.reapStaleRunningJobs();

            assertThat(job.getStatus()).isEqualTo(AgentJobStatus.RUNNING);
            assertThat(meterRegistry.counter("agent.job.zombie.reaped").count()).isZero();
            verifyNoInteractions(usageRecorder);
        }

        /**
         * The wedge this guards: a snapshot written by a NEWER schema version — the reverted-canary
         * case {@link ConfigSnapshot#fromJson} rejects by design. Accounting runs inside the same
         * transaction as the TIMED_OUT write, so a throw here rolls that write back, the job returns to
         * RUNNING, and the next sweep fails identically — forever, holding a concurrency slot.
         */
        @Test
        @DisplayName("a snapshot this server cannot read still terminalises the job, billed UNPRICED")
        void unreadableSnapshotStillTerminalisesTheJob() {
            UUID jobId = UUID.randomUUID();
            AgentJob job = runningJob(jobId, Instant.now().minusSeconds(1200), 600);
            job.setExecutionStartedAt(Instant.now().minusSeconds(1190));
            job.setConfigSnapshot(snapshotFromTheFuture());

            when(jobRepository.findStaleRunningJobs(any())).thenReturn(List.of(job));
            when(jobRepository.findByIdWithWorkspaceForUpdate(jobId)).thenReturn(java.util.Optional.of(job));
            when(jobRepository.transitionStatus(any(), any(), any(), any(), any()))
                    .thenReturn(1);
            when(jobRepository.findLlmUsageById(jobId))
                    .thenReturn(java.util.Optional.of(new AgentJobLlmUsage(2, 500, 100, 0, 0, 0)));
            org.mockito.Mockito.clearInvocations(usageRecorder);

            sweeper.reapStaleRunningJobs();

            verify(jobRepository)
                    .transitionStatus(
                            eq(jobId), eq(AgentJobStatus.TIMED_OUT), any(), any(), eq(Set.of(AgentJobStatus.RUNNING)));
            assertThat(meterRegistry.counter("agent.job.zombie.reaped").count()).isEqualTo(1.0);
            LlmUsageRecorder.LlmUsageSample sample = captureUnverifiable();
            assertThat(sample.price().pricingState()).isEqualTo(PricingState.UNPRICED);
            assertThat(sample.price().fundingSource()).isEqualTo(FundingSource.INSTANCE);
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
            AgentJob job = runningJob(jobId, Instant.now().minusSeconds(1200), 600);
            job.setConfigSnapshot(snapshotFromTheFuture());

            when(jobRepository.findStaleRunningJobs(any())).thenReturn(List.of(job));
            when(jobRepository.findByIdWithWorkspaceForUpdate(jobId)).thenReturn(java.util.Optional.of(job));
            when(jobRepository.transitionStatus(any(), any(), any(), any(), any()))
                    .thenReturn(1);

            sweeper.reapStaleRunningJobs();

            assertThat(meterRegistry.counter("agent.job.zombie.reaped").count()).isEqualTo(1.0);
        }
    }
}
