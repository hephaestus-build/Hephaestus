package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPrice;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPriceRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.PricingMode;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.PricingState;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Verifies orphan recovery, retry fencing, and claim eligibility against PostgreSQL. */
@DisplayName("Orphan recovery over PostgreSQL Integration")
class AgentOrphanRecoveryIntegrationTest extends BaseIntegrationTest {

    private AgentJobZombieSweeper sweeper;

    @Autowired
    private AgentJobLifecycleService lifecycleService;

    @Autowired
    private LlmUsageRecorder usageRecorder;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private AgentJobRepository jobRepository;

    @Autowired
    private WorkerRegistryRepository workerRegistryRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceAgentBindingRepository agentBindingRepository;

    @Autowired
    private LlmConnectionRepository connectionRepository;

    @Autowired
    private LlmModelRepository modelRepository;

    @Autowired
    private LlmModelPriceRepository priceRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Workspace workspace;
    private WorkspaceAgentBinding agentBinding;
    private LlmModel instanceModel;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        AgentProperties properties = mock(AgentProperties.class);
        when(properties.maxRetries()).thenReturn(5);
        sweeper = new AgentJobZombieSweeper(
                jobRepository,
                workerRegistryRepository,
                properties,
                objectMapper,
                transactionTemplate,
                lifecycleService,
                usageRecorder,
                meterRegistry,
                new AgentJobTelemetry(meterRegistry, io.micrometer.tracing.Tracer.NOOP));
        workspace = workspaceRepository.save(TestEntities.activeWorkspace("orphan-recovery-ws"));

        LlmConnection connection = connectionRepository.save(LlmCatalogTestFixtures.connection("orphan-recovery"));
        instanceModel =
                modelRepository.save(LlmCatalogTestFixtures.model(connection, "orphan-recovery-model", "test-model"));

        LlmModelPrice price = new LlmModelPrice();
        price.setModel(instanceModel);
        price.setPricingMode(PricingMode.NO_CHARGE);
        price.setNote("Integration-test model has no per-token charge");
        price.setEffectiveFrom(Instant.now().minusSeconds(60));
        priceRepository.save(price);

        WorkspaceAgentBinding binding = new WorkspaceAgentBinding();
        binding.setWorkspace(workspace);
        binding.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        binding.setEnabled(true);
        binding.setInstanceModel(instanceModel);
        agentBinding = agentBindingRepository.save(binding);
    }

    @Test
    @DisplayName("a dead worker's RUNNING job is requeued (retry_count++) and becomes claimable again")
    void orphanRecoveryRequeuesAndBecomesClaimable() {
        UUID jobId = runningJobOwnedBy("dead-replica", Instant.now().minus(Duration.ofMinutes(5)), 0);
        registerStaleWorker("dead-replica", Instant.now().minus(Duration.ofMinutes(5)));

        sweeper.recoverOrphanedJobs();

        AgentJob requeued = jobRepository.findById(jobId).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(AgentJobStatus.QUEUED);
        assertThat(requeued.getWorkerId()).isNull();
        assertThat(requeued.getRetryCount()).isEqualTo(1);

        fastForwardAvailableAt(jobId);

        assertThat(eligibleForClaim(jobId)).isTrue();
    }

    @Test
    @DisplayName("orphan requeue rotates the job token — the old token no longer authenticates, the new one does")
    void orphanRequeueRotatesTheJobToken() {
        UUID jobId = runningJobOwnedBy("dead-replica", Instant.now().minus(Duration.ofMinutes(5)), 0);
        registerStaleWorker("dead-replica", Instant.now().minus(Duration.ofMinutes(5)));
        AgentJob before = jobRepository.findById(jobId).orElseThrow();
        String oldTokenHash = before.getJobTokenHash();

        sweeper.recoverOrphanedJobs();

        AgentJob requeued = jobRepository.findById(jobId).orElseThrow();
        String newTokenHash = requeued.getJobTokenHash();
        assertThat(newTokenHash).isNotEqualTo(oldTokenHash);

        // The old token is dead: this mirrors JobTokenAuthenticationFilter#resolveJobRouting's lookup
        // (hash + status=RUNNING) — the requeue moved status to QUEUED too, so BOTH conditions now fail
        // for the old token even before considering the hash change.
        assertThat(jobRepository.findByJobTokenHashAndStatus(oldTokenHash, AgentJobStatus.RUNNING))
                .isEmpty();

        fastForwardAvailableAt(jobId);

        assertThat(eligibleForClaim(jobId)).isTrue();
        assertThat(jobRepository.findByJobTokenHashAndStatus(oldTokenHash, AgentJobStatus.RUNNING))
                .isEmpty();
    }

    @Test
    @DisplayName("a direct claim attempt made WHILE still backed off does not succeed")
    void claimAttemptWhileStillBackedOffDoesNotSucceed() {
        UUID jobId = runningJobOwnedBy("dead-replica-5", Instant.now().minus(Duration.ofMinutes(5)), 0);
        registerStaleWorker("dead-replica-5", Instant.now().minus(Duration.ofMinutes(5)));

        sweeper.recoverOrphanedJobs();

        AgentJob requeued = jobRepository.findById(jobId).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(AgentJobStatus.QUEUED);
        assertThat(requeued.getAvailableAt())
                .as("the backoff-computed available_at is still in the future")
                .isAfter(Instant.now());

        assertThat(eligibleForClaim(jobId)).isFalse();
        AgentJob stillQueued = jobRepository.findById(jobId).orElseThrow();
        assertThat(stillQueued.getStatus()).isEqualTo(AgentJobStatus.QUEUED);
    }

    @Test
    @DisplayName("a job whose available_at is in the future is not offered as a poll candidate")
    void jobWithFutureAvailableAtIsNotClaimed() {
        UUID jobId = runningJobOwnedBy("dead-replica-4", Instant.now().minus(Duration.ofMinutes(5)), 0);
        registerStaleWorker("dead-replica-4", Instant.now().minus(Duration.ofMinutes(5)));

        sweeper.recoverOrphanedJobs();

        // The requeue's backoff puts available_at far enough out that this is not a race with the clock.
        AgentJob requeued = jobRepository.findById(jobId).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(AgentJobStatus.QUEUED);
        assertThat(requeued.getAvailableAt()).isAfter(Instant.now());

        assertThat(jobRepository.findQueuedIdsOldestFirst(10))
                .as("a not-yet-eligible QUEUED job must not be offered as a poll candidate")
                .doesNotContain(jobId);
    }

    @Test
    @DisplayName("an orphan already at the retry cap is failed, not requeued again")
    void orphanPastRetryCapIsFailedNotRequeued() {
        UUID jobId = runningJobOwnedBy("dead-replica-2", Instant.now().minus(Duration.ofMinutes(5)), 5);
        registerStaleWorker("dead-replica-2", Instant.now().minus(Duration.ofMinutes(5)));

        sweeper.recoverOrphanedJobs();

        AgentJob failed = jobRepository.findById(jobId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(AgentJobStatus.FAILED);
        assertThat(failed.getErrorMessage()).contains("retry limit reached");
        assertThat(jobRepository.findQueuedIdsOldestFirst(10)).doesNotContain(jobId);
    }

    @Test
    @DisplayName(
            "requeueOrphan is fenced on worker_id — a stale caller cannot steal a job a live sibling has re-claimed")
    void requeueOrphanDoesNotStealAJobReclaimedBySomeoneElse() {
        UUID jobId = runningJobOwnedBy("live-sibling", Instant.now(), 0);

        // @Modifying queries need an active transaction (the sweeper normally provides one via
        // TransactionTemplate); wrap here too.
        String candidateNewToken = AgentJob.generateJobToken();
        int updated = transactionTemplate.execute(s -> jobRepository.requeueOrphan(
                jobId,
                "dead-replica",
                5,
                Instant.now(),
                candidateNewToken,
                AgentJob.computeTokenHash(candidateNewToken)));

        assertThat(updated)
                .as("the CAS must not match — the row's worker_id does not match the stale caller's")
                .isZero();
        AgentJob untouched = jobRepository.findById(jobId).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(AgentJobStatus.RUNNING);
        assertThat(untouched.getWorkerId()).isEqualTo("live-sibling");
        assertThat(untouched.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("requeueOrphan enforces the retry cap in SQL even if a caller forgets to check it first")
    void requeueOrphanRefusesPastTheRetryCapEvenUnchecked() {
        UUID jobId = runningJobOwnedBy("dead-replica-3", Instant.now(), 5);

        String candidateNewToken = AgentJob.generateJobToken();
        int updated = transactionTemplate.execute(s -> jobRepository.requeueOrphan(
                jobId,
                "dead-replica-3",
                5,
                Instant.now(),
                candidateNewToken,
                AgentJob.computeTokenHash(candidateNewToken)));

        assertThat(updated).isZero();
        AgentJob unchanged = jobRepository.findById(jobId).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(AgentJobStatus.RUNNING);
        assertThat(unchanged.getRetryCount()).isEqualTo(5);
    }

    private UUID runningJobOwnedBy(String workerId, Instant startedAt, int retryCount) {
        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setStatus(AgentJobStatus.RUNNING);
        job.setConfigSnapshot(new ConfigSnapshot(
                        ConfigSnapshot.SCHEMA_VERSION,
                        "openai-completions",
                        "https://api.openai.com/v1",
                        "test-model",
                        null,
                        null,
                        null,
                        false,
                        FundingSource.INSTANCE,
                        instanceModel.getConnection().getId(),
                        instanceModel.getId(),
                        workspace.getId(),
                        600,
                        false,
                        null,
                        null)
                .withPriceSnapshot(new LlmPriceSnapshot(
                        FundingSource.INSTANCE, PricingState.NO_CHARGE, null, null, null, null, null, null))
                .toJson(objectMapper));
        job.setWorkerId(workerId);
        job.setStartedAt(startedAt);
        job.setRetryCount(retryCount);
        return jobRepository.saveAndFlush(job).getId();
    }

    private void registerStaleWorker(String workerId, Instant lastHeartbeat) {
        WorkerRegistry w = new WorkerRegistry();
        w.setWorkerId(workerId);
        w.setLastHeartbeat(lastHeartbeat);
        w.setRegisteredAt(lastHeartbeat);
        workerRegistryRepository.saveAndFlush(w);
    }

    private boolean eligibleForClaim(UUID jobId) {
        return transactionTemplate.execute(status -> jobRepository
                .findByIdQueuedForUpdateSkipLocked(jobId, Instant.now())
                .isPresent());
    }

    private void fastForwardAvailableAt(UUID jobId) {
        transactionTemplate.executeWithoutResult(status -> {
            AgentJob job = jobRepository.findById(jobId).orElseThrow();
            job.setAvailableAt(Instant.now().minus(Duration.ofSeconds(1)));
            jobRepository.saveAndFlush(job);
        });
    }
}
