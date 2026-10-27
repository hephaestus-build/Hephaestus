package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.UUID;
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

    // This context has worker.enabled=true and NO hephaestus.worker.control.endpoint. required=false so a regression
    // (identity bound only when WSS is configured) fails on the assertion, not on context load.
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
        UUID id = runningJob("w-alive");
        transactionTemplate.execute(s -> jobRepository.lease(id, LEASE_TTL_SECONDS));

        assertThat(expired()).extracting(OrphanedJobRef::getJobId).doesNotContain(id);
    }

    @Test
    @DisplayName("a running attempt whose lease expired is recovered")
    void shouldRecoverTheAttemptWhenItsLeaseExpired() {
        UUID id = runningJob("w-gone");
        jdbc.update("UPDATE review_job SET leased_until = now() - interval '1 minute' WHERE id = ?", id);

        assertThat(expired()).extracting(OrphanedJobRef::getJobId).containsExactly(id);
    }

    @Test
    @DisplayName("a running attempt without a lease, from a version before leases, is recovered")
    void shouldRecoverTheAttemptWhenItHasNoLease() {
        UUID id = runningJob("w-old");

        assertThat(expired()).extracting(OrphanedJobRef::getJobId).containsExactly(id);
    }

    @Test
    @DisplayName("a worker's heartbeat renews the leases of its own running attempts, and of no other worker's")
    void shouldRenewOnlyItsOwnLeasesWhenAWorkerBeats() {
        UUID mine = runningJob("w-me");
        UUID theirs = runningJob("w-other");
        jdbc.update("UPDATE review_job SET leased_until = now() - interval '1 minute'");

        int renewed = transactionTemplate.execute(s -> jobRepository.renewLeases("w-me", LEASE_TTL_SECONDS));

        assertThat(renewed).isEqualTo(1);
        assertThat(expired())
                .extracting(OrphanedJobRef::getJobId)
                .containsExactly(theirs)
                .doesNotContain(mine);
    }

    @Autowired
    private AgentProperties agentProperties;

    @Test
    @DisplayName("this worker's heartbeat renews the lease of an attempt that it runs")
    void shouldRenewTheLeaseWhenTheWorkerThatRunsTheAttemptBeats() {
        assertThat(workerProperties).isNotNull();
        var reporter = new WorkerLivenessReporter(
                workerRegistryRepository,
                jobRepository,
                transactionTemplate,
                agentProperties,
                workerProperties,
                new SimpleMeterRegistry());
        UUID id = runningJob(workerProperties.resolvedWorkerId());
        jdbc.update("UPDATE review_job SET leased_until = now() - interval '1 minute' WHERE id = ?", id);

        reporter.beat();

        assertThat(expired()).extracting(OrphanedJobRef::getJobId).doesNotContain(id);
    }

    @Test
    @DisplayName("requeueOrphan is a CAS: first caller wins (1), the second is a no-op (0)")
    void requeueOrphanIsCasIdempotent() {
        UUID id = runningJob("w-stale");

        // requeueOrphan is @Modifying — wrap in a tx, as the sweeper does. Fenced on worker_id:
        // "w-stale" is the row's actual owner both times, so the fence itself doesn't
        // block the second call; status alone (no longer RUNNING after the first win) does.
        int firstWin = transactionTemplate.execute(s -> jobRepository.requeueOrphan(id, "w-stale", 5, Instant.now()));
        int secondWin = transactionTemplate.execute(s -> jobRepository.requeueOrphan(id, "w-stale", 5, Instant.now()));
        assertThat(firstWin).isEqualTo(1);
        assertThat(secondWin).isEqualTo(0); // already QUEUED — lost the race

        ReviewJob requeued = jobRepository.findById(id).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(requeued.getWorkerId()).isNull();
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
    @DisplayName("worker identity binds on the worker role WITHOUT a WSS endpoint (orphan recovery always armed)")
    void workerIdentityBoundWithoutWssEndpoint() {
        assertThat(workerProperties)
                .as("WorkerProperties must bind on the worker role independent of the WSS control endpoint")
                .isNotNull();
        String id = workerProperties.resolvedWorkerId();
        assertThat(id).isNotBlank();
        // Stable per process (the instance suffix is computed once) — both the executor's job-ownership
        // stamp and the WSS JWT subject rely on this being identical across calls.
        assertThat(id).isEqualTo(workerProperties.resolvedWorkerId());
    }

    // ── helpers ──

    private List<OrphanedJobRef> expired() {
        return jobRepository.findExpiredLeases(jobRepository.databaseNow());
    }

    private UUID runningJob(String workerId) {
        ReviewJob job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());

        job.setStatus(ReviewJobStatus.RUNNING);
        job.setConfigSnapshot(objectMapper.createObjectNode());
        job.setWorkerId(workerId);
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
