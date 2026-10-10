package de.tum.cit.aet.hephaestus.agent.job;

import static java.util.Objects.requireNonNull;
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
import de.tum.cit.aet.hephaestus.agent.handler.AdmittedObservationFixtures;
import de.tum.cit.aet.hephaestus.agent.handler.ObservationAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.PricingState;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRevisionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private PracticeRevisionRepository practiceRevisionRepository;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private IdentityProviderRepository identityProviderRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private Workspace workspace;
    private LlmModel instanceModel;
    private Practice practice;
    private User developer;

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
                new AgentJobTelemetry(meterRegistry, Tracer.NOOP));
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
        agentBindingRepository.save(binding);

        practice = new Practice();
        PracticeTestEvidence.configure(practice, ArtifactKinds.PULL_REQUEST);
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        practice.setWorkspace(workspace);
        practice.setSlug("orphan-recovery-practice");
        practice.setName("Orphan recovery practice");
        practice.setCriteria("The first definition");
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        PracticeTestEvidence.configure(practice, ScmSignals.PULL_REQUEST_OPENED);
        practice = practiceRepository.saveAndFlush(practice);
        practice.setCurrentRevision(practiceRevisionRepository.save(new PracticeRevision(practice, 1)));
        practice = practiceRepository.saveAndFlush(practice);
        IdentityProvider provider = identityProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> identityProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        developer = userRepository.save(TestUserFactory.createUser(100L, "developer", provider));
    }

    @Test
    @DisplayName("a dead worker's RUNNING job is requeued (retry_count++) and becomes claimable again")
    void orphanRecoveryRequeuesAndBecomesClaimable() {
        UUID jobId = runningJobOwnedBy("dead-replica", Instant.now().minus(Duration.ofMinutes(5)), 0);
        registerStaleWorker("dead-replica");

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
        registerStaleWorker("dead-replica");
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
        registerStaleWorker("dead-replica-5");

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
        registerStaleWorker("dead-replica-4");

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
        registerStaleWorker("dead-replica-2");

        sweeper.recoverOrphanedJobs();

        AgentJob failed = jobRepository.findById(jobId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(AgentJobStatus.FAILED);
        assertThat(failed.getErrorMessage()).contains("retry limit is reached");
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

    @Test
    @DisplayName("an orphan whose observations were admitted fails with them kept, after its practice changed")
    void shouldFailWithTheAdmittedObservationsKeptWhenTheOwningWorkerIsLostAfterAdmission() {
        UUID jobId = runningJobOwnedBy("dead-replica-6", Instant.now().minus(Duration.ofMinutes(5)), 0);
        Observation observed = admitWithObservation(jobId);
        changePracticeDefinition();
        registerStaleWorker("dead-replica-6");
        AgentJob before = jobRepository.findById(jobId).orElseThrow();

        sweeper.recoverOrphanedJobs();

        AgentJob failed = jobRepository.findById(jobId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(AgentJobStatus.FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo(ObservationAdmissionService.INTERRUPTED_AFTER_ADMISSION);
        assertThat(failed.getRetryCount()).isZero();
        assertThat(failed.getJobTokenHash()).isEqualTo(before.getJobTokenHash());
        assertThat(failed.getMetadata()).isEqualTo(before.getMetadata());
        assertThat(failed.getEvidenceSnapshot()).isEqualTo(before.getEvidenceSnapshot());
        Observation kept = observationRepository.findById(observed.getId()).orElseThrow();
        assertThat(kept.getAgentJobId()).isEqualTo(jobId);
        PracticeRevision originalRevision = requireNonNull(observed.getPracticeRevision());
        Long originalRevisionId = requireNonNull(originalRevision.getId());
        assertThat(requireNonNull(kept.getPracticeRevision()).getId()).isEqualTo(originalRevisionId);
        assertThat(practiceRevisionRepository
                        .findById(originalRevisionId)
                        .orElseThrow()
                        .getCriteria())
                .isEqualTo("The first definition");
        assertThat(practiceRepository.findById(practice.getId()).orElseThrow().getCriteria())
                .isEqualTo("The changed definition");
        assertThat(kept.getEvidence()).isEqualTo(observed.getEvidence());
        assertThat(jobRepository.findQueuedIdsOldestFirst(10)).doesNotContain(jobId);
    }

    @Test
    @DisplayName("requeueOrphan refuses an admitted attempt in SQL, and only its owner's attempt can end it")
    void shouldRefuseToRequeueAnAdmittedAttemptWhenAnyCallerTries() {
        UUID admitted = runningJobOwnedBy("owner", Instant.now(), 0);
        admitWithObservation(admitted);
        UUID unadmitted = runningJobOwnedBy("owner", Instant.now(), 0);

        String token = AgentJob.generateJobToken();
        int requeued = transactionTemplate.execute(s -> jobRepository.requeueOrphan(
                admitted, "owner", 5, Instant.now(), token, AgentJob.computeTokenHash(token)));
        int byStranger = transactionTemplate.execute(
                s -> jobRepository.failAdmittedOwnedBy(admitted, "stranger", 0, Instant.now(), "lost"));
        int byLaterAttempt = transactionTemplate.execute(
                s -> jobRepository.failAdmittedOwnedBy(admitted, "owner", 1, Instant.now(), "lost"));
        int notAdmitted = transactionTemplate.execute(
                s -> jobRepository.failAdmittedOwnedBy(unadmitted, "owner", 0, Instant.now(), "lost"));

        assertThat(requeued).isZero();
        assertThat(byStranger).isZero();
        assertThat(byLaterAttempt).isZero();
        assertThat(notAdmitted).isZero();
        AgentJob untouched = jobRepository.findById(admitted).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(AgentJobStatus.RUNNING);
        assertThat(untouched.getRetryCount()).isZero();
        assertThat(jobRepository.findById(unadmitted).orElseThrow().getStatus()).isEqualTo(AgentJobStatus.RUNNING);
    }

    @ParameterizedTest(name = "admission committed: {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("recovery waiting on the admission's row lock decides on what the admission left behind")
    void shouldDecideOnTheCommittedAdmissionWhenRecoveryWaitsForItsRowLock(boolean committed) throws Exception {
        UUID jobId = runningJobOwnedBy("dead-replica-7", Instant.now().minus(Duration.ofMinutes(5)), 0);
        registerStaleWorker("dead-replica-7");

        whileRecoveryWaits(
                jobId,
                committed,
                job -> job.setMetadata(objectMapper
                        .createObjectNode()
                        .put(ObservationAdmissionService.DIGEST_METADATA_KEY, "admitted-digest")));

        AgentJob after = jobRepository.findById(jobId).orElseThrow();
        if (committed) {
            assertThat(after.getStatus()).isEqualTo(AgentJobStatus.FAILED);
            assertThat(after.getRetryCount()).isZero();
            assertThat(ObservationAdmissionService.isAdmitted(after)).isTrue();
        } else {
            assertThat(after.getStatus()).isEqualTo(AgentJobStatus.QUEUED);
            assertThat(after.getRetryCount()).isEqualTo(1);
            assertThat(ObservationAdmissionService.isAdmitted(after)).isFalse();
        }
    }

    @Test
    @DisplayName("recovery leaves alone an attempt a new claim took while it waited for the row lock")
    void shouldLeaveTheNewClaimAloneWhenTheOrphanWasReclaimedWhileRecoveryWaited() throws Exception {
        UUID jobId = runningJobOwnedBy("dead-replica-8", Instant.now().minus(Duration.ofMinutes(5)), 5);
        registerStaleWorker("dead-replica-8");

        whileRecoveryWaits(jobId, true, job -> {
            job.setWorkerId("live-sibling");
            job.setRetryCount(6);
        });

        AgentJob after = jobRepository.findById(jobId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AgentJobStatus.RUNNING);
        assertThat(after.getWorkerId()).isEqualTo("live-sibling");
        assertThat(after.getRetryCount()).isEqualTo(6);
        assertThat(after.getErrorMessage()).isNull();
    }

    /**
     * Holds the job's row lock as the admission does, changes it, and lets the sweep run against it: the
     * sweep blocks on the lock, then decides on what this transaction committed or rolled back.
     */
    private void whileRecoveryWaits(UUID jobId, boolean commit, Consumer<AgentJob> change) throws Exception {
        var locked = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> transactionTemplate.executeWithoutResult(tx -> {
                AgentJob job =
                        jobRepository.findByIdWithWorkspaceForUpdate(jobId).orElseThrow();
                change.accept(job);
                jobRepository.saveAndFlush(job);
                locked.countDown();
                awaitBlockedTransaction();
                if (!commit) tx.setRollbackOnly();
            }));
            var recovery = pool.submit(() -> {
                assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
                sweeper.recoverOrphanedJobs();
                return null;
            });
            holder.get(30, TimeUnit.SECONDS);
            recovery.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitBlockedTransaction() {
        Integer holderPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
        Awaitility.await().pollInSameThread().atMost(30, TimeUnit.SECONDS).until(() -> {
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            return Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT FROM pg_stat_activity WHERE CAST(? AS integer) = ANY(pg_blocking_pids(pid)))",
                    Boolean.class,
                    holderPid));
        });
    }

    /**
     * An admission as it is persisted: an observation of a practice revision, citing this job's capture,
     * and the digest on the job's metadata.
     */
    private Observation admitWithObservation(UUID jobId) {
        UUID id = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                id,
                "occurrence-" + id,
                jobId,
                workspace.getId(),
                practice.getId(),
                practice.getCurrentRevision().getId(),
                "scm.pull_request",
                42L,
                developer.getId(),
                "Observation title",
                "NOT_MET",
                "MAJOR",
                AdmittedObservationFixtures.evidence(
                                jobId, "scm.pull-request.core", "context/metadata.json", "metadata.json", "example")
                        .toString(),
                null,
                null,
                Instant.now(),
                "LIVE");
        transactionTemplate.executeWithoutResult(status -> {
            AgentJob job = jobRepository.findById(jobId).orElseThrow();
            job.setMetadata(objectMapper
                    .createObjectNode()
                    .put("pull_request_id", 42)
                    .put(ObservationAdmissionService.DIGEST_METADATA_KEY, "admitted-digest"));
            job.setEvidenceSnapshot(objectMapper
                    .createObjectNode()
                    .set("manifest", objectMapper.createObjectNode().put("contractVersion", "1.2.0")));
            jobRepository.saveAndFlush(job);
        });
        return observationRepository.findById(id).orElseThrow();
    }

    /** The practice's definition moves on after the admission, as a later edit would. */
    private void changePracticeDefinition() {
        practice.setCriteria("The changed definition");
        practice.setCurrentRevision(practiceRevisionRepository.save(new PracticeRevision(practice, 2)));
        practice = practiceRepository.saveAndFlush(practice);
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
                        null,
                        FundingSource.INSTANCE,
                        instanceModel.getConnection().getId(),
                        instanceModel.getId(),
                        workspace.getId(),
                        600,
                        false,
                        null,
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

    private void registerStaleWorker(String workerId) {
        // The orphan query judges the lease on the database clock.
        Instant lastHeartbeat = requireNonNull(jdbc.queryForObject(
                        "SELECT now() - make_interval(secs => ?)",
                        OffsetDateTime.class,
                        AgentProperties.WORKER_LEASE_TTL.plusMinutes(1).toSeconds()))
                .toInstant();
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
