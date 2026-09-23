package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.handler.ObservationAdmissionService.AdmissionIdentity;
import de.tum.cit.aet.hephaestus.agent.handler.ObservationAdmissionService.StaleAttemptException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class ObservationAdmissionConcurrencyIntegrationTest extends BaseIntegrationTest {

    private static final int TIMEOUT_SECONDS = 30;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private PullRequestReviewHandler reviewHandler;

    @Autowired
    private ObservationAdmissionService admission;

    @Autowired
    private AgentJobRepository jobs;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper mapper;

    @Test
    void shouldRejectStaleRefusalWhenOwnershipChangesWhileWaitingForTheJobLock() throws Exception {
        var workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace(
                "admission-race-" + UUID.randomUUID().toString().substring(0, 8)));
        var job = new AgentJob();
        job.setWorkspace(workspace);
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setConfigSnapshot(mapper.createObjectNode());
        job.setStatus(AgentJobStatus.RUNNING);
        job.setWorkerId("original-worker");
        var metadata = mapper.createObjectNode().put("preserved", "value");
        job.setMetadata(metadata);
        var saved = jobs.saveAndFlush(job);
        var identity = new AdmissionIdentity(saved.getId(), workspace.getId(), 0, "original-worker");
        var locked = new CountDownLatch(1);
        var dispatched = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var owner = pool.submit(() -> transactions.executeWithoutResult(tx -> {
                var current =
                        jobs.findByIdWithWorkspaceForUpdate(identity.jobId()).orElseThrow();
                locked.countDown();
                await(dispatched);
                awaitBlockedTransaction();
                current.setRetryCount(1);
                current.setWorkerId("replacement-worker");
                current.setStatus(AgentJobStatus.CANCELLED);
                jobs.save(current);
            }));
            var refusal = pool.submit(() -> {
                await(locked);
                dispatched.countDown();
                assertThatThrownBy(() -> admission.recordRefusal(
                                identity, "INVALID_EVIDENCE", "stale submission", mapper.createArrayNode()))
                        .isInstanceOf(StaleAttemptException.class);
            });
            owner.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            refusal.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
        var reloaded = jobs.findById(identity.jobId()).orElseThrow();
        assertThat(reloaded.getMetadata()).isEqualTo(metadata);
        assertThat(reloaded.getRetryCount()).isEqualTo(1);
        assertThat(reloaded.getWorkerId()).isEqualTo("replacement-worker");
        assertThat(reloaded.getStatus()).isEqualTo(AgentJobStatus.CANCELLED);
    }

    @Test
    void shouldReleaseTheJobLockDuringVerificationAndFenceItsLaterPublication() throws Exception {
        var workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace(
                "verification-race-" + UUID.randomUUID().toString().substring(0, 8)));
        var job = new AgentJob();
        job.setWorkspace(workspace);
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setConfigSnapshot(mapper.createObjectNode());
        job.setStatus(AgentJobStatus.RUNNING);
        job.setWorkerId("verifying-worker");
        job.setMetadata(mapper.createObjectNode());
        var saved = jobs.saveAndFlush(job);
        var identity = new AdmissionIdentity(saved.getId(), workspace.getId(), 0, "verifying-worker");
        var verifying = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
                    assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                                    .isActualTransactionActive())
                            .isFalse();
                    verifying.countDown();
                    await(release);
                    return (de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedObservations) admitted -> {};
                })
                .when(reviewHandler)
                .prepareObservations(
                        org.mockito.ArgumentMatchers.argThat(
                                candidate -> candidate.getId().equals(saved.getId())),
                        org.mockito.ArgumentMatchers.any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var admissionResult =
                    pool.submit(() -> assertThatThrownBy(() -> admission.admit(identity, mapper.createArrayNode()))
                            .isInstanceOf(StaleAttemptException.class));
            await(verifying);
            var reassignment = pool.submit(() -> transactions.executeWithoutResult(tx -> {
                var current = jobs.findByIdWithWorkspaceForUpdate(saved.getId()).orElseThrow();
                current.setRetryCount(1);
                current.setWorkerId("replacement-worker");
                jobs.save(current);
            }));
            reassignment.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            release.countDown();
            admissionResult.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
        var reloaded = jobs.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getRetryCount()).isEqualTo(1);
        assertThat(reloaded.getMetadata()).isEqualTo(mapper.createObjectNode());
    }

    private void awaitBlockedTransaction() {
        Integer holderPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
        org.awaitility.Awaitility.await()
                .pollInSameThread()
                .atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .until(() -> {
                    // Refresh activity statistics on the transaction holding the lock.
                    jdbc.execute("SELECT pg_stat_clear_snapshot()");
                    return Boolean.TRUE.equals(jdbc.queryForObject(
                            "SELECT EXISTS (SELECT FROM pg_stat_activity WHERE CAST(? AS integer) = ANY(pg_blocking_pids(pid)))",
                            Boolean.class,
                            holderPid));
                });
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
