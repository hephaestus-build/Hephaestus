package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Real-Postgres proof of lease recovery, which makes running more than one worker replica safe. Mock-based unit tests
 * cannot exercise the native lease queries on the database clock or the CAS {@code requeueOrphan}, so the actual
 * coordination guarantees live here.
 */
@DisplayName("Review job lease SQL (Postgres) Integration")
class ReviewJobLeaseIntegrationTest extends BaseIntegrationTest {

    private static final long LEASE_TTL_SECONDS = AgentProperties.WORKER_LEASE_TTL.toSeconds();
    private static final UUID LEASE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OTHER_LEASE = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Autowired
    private ReviewJobRepository jobRepository;

    @Autowired
    private WorkerRegistryRepository workerRegistryRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    // required=false, so that a context without the worker's identity fails on the assertion, not on context load.
    @Autowired(required = false)
    private WorkerProperties workerProperties;

    private Workspace workspace;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        workspace = workspaceRepository.save(TestEntities.activeWorkspace("lease-ws"));
    }

    @Test
    @DisplayName("a running attempt whose lease holds is not recovered")
    void shouldKeepTheAttemptWhenItsLeaseHolds() {
        UUID id = runningJob(LEASE);
        transactionTemplate.execute(s -> jobRepository.lease(id, LEASE_TTL_SECONDS));

        assertThat(expired()).extracting(OrphanedJobRef::getJobId).doesNotContain(id);
    }

    @Test
    @DisplayName("a running attempt whose lease expired is recovered with that lease")
    void shouldRecoverTheAttemptWhenItsLeaseExpired() {
        UUID id = runningJob(LEASE);
        jdbc.update("UPDATE review_job SET leased_until = now() - interval '1 minute' WHERE id = ?", id);

        assertThat(expired()).singleElement().satisfies(orphan -> {
            assertThat(orphan.getJobId()).isEqualTo(id);
            assertThat(orphan.getLeaseId()).isEqualTo(LEASE);
        });
    }

    @Test
    @DisplayName("a running attempt without an expiry, from a version before leases, is recovered")
    void shouldRecoverTheAttemptWhenItHasNoExpiry() {
        UUID id = runningJob(LEASE);

        assertThat(expired()).extracting(OrphanedJobRef::getJobId).containsExactly(id);
    }

    @Test
    @DisplayName("a renewal names one attempt's exact lease, and never revives a lease that expired")
    void shouldRenewOnlyTheExactLiveLease() {
        UUID mine = runningJob(LEASE);
        UUID theirs = runningJob(OTHER_LEASE);
        transactionTemplate.execute(s -> {
            jobRepository.lease(mine, LEASE_TTL_SECONDS);
            return jobRepository.lease(theirs, LEASE_TTL_SECONDS);
        });
        long workspaceId = workspace.getId();

        assertThat(renew(mine, workspaceId, 0, LEASE)).isEqualTo(1);
        assertThat(renew(mine, workspaceId, 0, OTHER_LEASE)).as("another lease").isZero();
        assertThat(renew(mine, workspaceId, 1, LEASE)).as("another attempt").isZero();
        assertThat(renew(mine, workspaceId + 1, 0, LEASE))
                .as("another workspace")
                .isZero();
        jdbc.update("UPDATE review_job SET leased_until = now() - interval '1 second' WHERE id = ?", theirs);
        assertThat(renew(theirs, workspaceId, 0, OTHER_LEASE))
                .as("an expired lease")
                .isZero();
        assertThat(expired()).extracting(OrphanedJobRef::getJobId).containsExactly(theirs);
    }

    @Test
    @DisplayName("only the exact live lease of a running attempt counts as live")
    void shouldTreatOnlyTheExactLiveLeaseAsLive() {
        UUID id = runningJob(LEASE);
        transactionTemplate.execute(s -> jobRepository.lease(id, LEASE_TTL_SECONDS));
        long workspaceId = workspace.getId();

        assertThat(jobRepository.hasLiveLease(id, workspaceId, 0, LEASE)).isTrue();
        assertThat(jobRepository.hasLiveLease(id, workspaceId, 0, OTHER_LEASE)).isFalse();
        assertThat(jobRepository.hasLiveLease(id, workspaceId, 1, LEASE)).isFalse();
        assertThat(jobRepository.hasLiveLease(id, workspaceId + 1, 0, LEASE)).isFalse();
        jdbc.update("UPDATE review_job SET leased_until = now() - interval '1 second' WHERE id = ?", id);
        assertThat(jobRepository.hasLiveLease(id, workspaceId, 0, LEASE)).isFalse();
    }

    @Autowired
    private AgentProperties agentProperties;

    @Test
    @DisplayName("a heartbeat stops each local attempt whose lease did not renew")
    void shouldStopTheAttemptsWhoseLeaseDidNotRenewWhenTheWorkerBeats() {
        assertThat(workerProperties).isNotNull();
        ReviewJobExecutor executor = mock(ReviewJobExecutor.class);
        var lost = Map.of(UUID.randomUUID(), new ReviewJobExecutor.LocalAttempt(workspace.getId(), 0, LEASE));
        when(executor.renewLeases()).thenReturn(lost);
        var reporter = new WorkerLivenessReporter(
                workerRegistryRepository,
                transactionTemplate,
                Optional.of(executor),
                agentProperties,
                workerProperties,
                new SimpleMeterRegistry());

        reporter.beat();

        verify(executor, timeout(5_000)).stopEndedAttempts(lost);
        assertThat(workerRegistryRepository.findById(workerProperties.resolvedWorkerId()))
                .isPresent();
    }

    @Test
    @DisplayName("a renewal that waits on the requeue's row lock renews nothing once the attempt is requeued")
    void shouldNotRenewALeaseThatARequeueTookUnderTheRowLock() throws Exception {
        UUID id = runningJob(LEASE);
        transactionTemplate.execute(s -> jobRepository.lease(id, LEASE_TTL_SECONDS));
        var locked = new CountDownLatch(1);
        var requeue = new CountDownLatch(1);

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> sweeper = threads.submit(() -> transactionTemplate.execute(s -> {
                assertThat(jobRepository.lockById(id)).isPresent();
                locked.countDown();
                await(requeue);
                return jobRepository.requeueOrphan(id, LEASE, 5, Instant.now());
            }));
            await(locked);
            Future<Integer> heartbeat = threads.submit(() -> renew(id, workspace.getId(), 0, LEASE));
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                            "SELECT EXISTS (SELECT 1 FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid"
                                    + " WHERE NOT l.granted AND a.query LIKE '%UPDATE review_job SET leased_until%')",
                            Boolean.class)));
            requeue.countDown();

            assertThat(sweeper.get(30, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(heartbeat.get(30, TimeUnit.SECONDS))
                    .as("the requeued attempt no longer holds the lease")
                    .isZero();
        }
        ReviewJob requeued = jobRepository.findById(id).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(requeued.getLeaseId()).isNull();
    }

    @Autowired
    private ReviewJobRecovery recovery;

    @Test
    @DisplayName("an attempt that 0.x claimed and the 1.0 changelog counted as a drain is requeued with no retry spent")
    void shouldRequeueACutoverAttemptWithoutSpendingARetry() {
        UUID id = runningJob(LEASE);
        // In 0.x the attempt was number 1 with no drain: it had spent 1 retry. The changelog then counted one drain.
        jdbc.update("UPDATE review_job SET attempt = 1, drain_count = 1, leased_until = NULL WHERE id = ?", id);

        assertThat(expired()).extracting(OrphanedJobRef::getJobId).contains(id);
        transactionTemplate.execute(s -> recovery.recover(id, LEASE, 1, null));

        ReviewJob requeued = jobRepository.findById(id).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(requeued.getAttempt()).isEqualTo(2);
        assertThat(requeued.spentAttempts())
                .as("the upgrade spent no retry: as many as in 0.x")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("requeueOrphan is a CAS: first caller wins (1), the second is a no-op (0)")
    void requeueOrphanIsCasIdempotent() {
        UUID id = runningJob(LEASE);

        // requeueOrphan is @Modifying — wrap in a tx, as the sweeper does. Fenced on the lease: LEASE is the row's
        // lease at the first call, and the requeue clears it, so the second call matches nothing.
        int firstWin = transactionTemplate.execute(s -> jobRepository.requeueOrphan(id, LEASE, 5, Instant.now()));
        int secondWin = transactionTemplate.execute(s -> jobRepository.requeueOrphan(id, LEASE, 5, Instant.now()));
        assertThat(firstWin).isEqualTo(1);
        assertThat(secondWin).isEqualTo(0); // already QUEUED — lost the race

        ReviewJob requeued = jobRepository.findById(id).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(requeued.getLeaseId()).isNull();
        assertThat(requeued.getAttempt()).isEqualTo(1);
    }

    @Test
    @DisplayName("deleteStale removes long-dead registrations but keeps fresh ones")
    void deleteStalePurgesDeadWorkers() {
        registerWorker("w-fresh", Instant.now());
        registerWorker("w-dead", Instant.now().minus(Duration.ofHours(2)));

        int removed = transactionTemplate.execute(
                s -> workerRegistryRepository.deleteStale(Duration.ofHours(1).toSeconds()));

        assertThat(removed).isEqualTo(1);
        assertThat(workerRegistryRepository.findById("w-fresh")).isPresent();
        assertThat(workerRegistryRepository.findById("w-dead")).isEmpty();
    }

    @Test
    @DisplayName("worker identity binds on the worker role with no other setting (orphan recovery always armed)")
    void workerIdentityBindsOnTheWorkerRoleAlone() {
        assertThat(workerProperties)
                .as("WorkerProperties must bind on the worker role alone")
                .isNotNull();
        String id = workerProperties.resolvedWorkerId();
        assertThat(id).isNotBlank();
        // Stable per process (the instance suffix is computed once): the registry row and the sandbox labels rely on
        // it.
        assertThat(id).isEqualTo(workerProperties.resolvedWorkerId());
    }

    // ── helpers ──

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private List<OrphanedJobRef> expired() {
        return jobRepository.findExpiredLeases(jobRepository.databaseNow());
    }

    private int renew(UUID id, long workspaceId, int attempt, UUID lease) {
        return Objects.requireNonNull(transactionTemplate.execute(
                s -> jobRepository.renewLease(id, workspaceId, attempt, lease, LEASE_TTL_SECONDS)));
    }

    private UUID runningJob(UUID lease) {
        ReviewJob job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());

        job.setStatus(ReviewJobStatus.RUNNING);
        job.setConfigSnapshot(objectMapper.createObjectNode());
        job.setLeaseId(lease);
        job.setStartedAt(Instant.now());
        return jobRepository
                .saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job))
                .getId();
    }

    private void registerWorker(String workerId, Instant lastHeartbeat) {
        WorkerRegistry w = new WorkerRegistry();
        w.setWorkerId(workerId);
        w.setLastHeartbeat(lastHeartbeat);
        w.setRegisteredAt(lastHeartbeat);
        workerRegistryRepository.saveAndFlush(w);
    }
}
