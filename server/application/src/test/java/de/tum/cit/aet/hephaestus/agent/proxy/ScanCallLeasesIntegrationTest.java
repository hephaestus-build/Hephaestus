package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Two replicas, each with its own leases, share one attempt's scan call lease through its row. */
@Tag("integration")
class ScanCallLeasesIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ReviewJobRepository jobs;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private ScanCallLeases replicaA;
    private ScanCallLeases replicaB;
    private ReviewJob job;

    @BeforeEach
    void setUp() {
        replicaA = new ScanCallLeases(jobs);
        replicaB = new ScanCallLeases(jobs);
        Workspace workspace = workspaces.save(TestEntities.activeWorkspace(
                "scan-lease-" + UUID.randomUUID().toString().substring(0, 8)));
        job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setProcessingStatus(ProcessingStatus.PENDING);
        job.setConfigSnapshot(mapper.createObjectNode());
        job = jobs.save(RuntimeJobTestData.persistParent(jdbc, job));
    }

    @AfterEach
    void tearDown() {
        replicaA.destroy();
        replicaB.destroy();
    }

    @Test
    void shouldLetOneCallOfAnAttemptHoldTheLeaseWhicheverReplicaServesIt() {
        UUID leaseA = replicaA.take(job.getId(), job.getWorkspaceId()).orElseThrow();

        assertThat(replicaB.take(job.getId(), job.getWorkspaceId())).isEmpty();

        replicaA.release(job.getId(), job.getWorkspaceId(), leaseA);
        assertThat(replicaB.take(job.getId(), job.getWorkspaceId())).isPresent();
    }

    @Test
    void shouldLetAnotherReplicaTakeTheLeaseOfAReplicaThatStoppedRenewingIt() {
        UUID stale = replicaA.take(job.getId(), job.getWorkspaceId()).orElseThrow();
        expire();

        UUID taken = replicaB.take(job.getId(), job.getWorkspaceId()).orElseThrow();
        // The stopped replica's late release must not free the lease that the other replica took.
        replicaA.release(job.getId(), job.getWorkspaceId(), stale);

        assertThat(lease()).isEqualTo(taken);
        assertThat(replicaA.take(job.getId(), job.getWorkspaceId())).isEmpty();
    }

    @Test
    void shouldExtendTheLeasesThatAReplicaHoldsWhileItsCallsRun() {
        assertThat(replicaA.take(job.getId(), job.getWorkspaceId())).isPresent();
        jdbc.update("UPDATE review_job SET scan_call_until = now() + interval '2 seconds' WHERE id = ?", job.getId());

        replicaA.renew();

        OffsetDateTime until = jdbc.queryForObject(
                "SELECT scan_call_until FROM review_job WHERE id = ?", OffsetDateTime.class, job.getId());
        assertThat(until)
                .isAfter(OffsetDateTime.now(ZoneOffset.UTC)
                        .plus(ScanCallLeases.TTL)
                        .minusSeconds(10));
    }

    @Test
    void shouldTakeNoLeaseOfAJobInAnotherWorkspace() {
        assertThat(replicaA.take(job.getId(), job.getWorkspaceId() + 1)).isEmpty();
        assertThat(lease()).isNull();
    }

    private void expire() {
        jdbc.update("UPDATE review_job SET scan_call_until = now() - interval '1 second' WHERE id = ?", job.getId());
    }

    private @Nullable UUID lease() {
        return jdbc.queryForObject("SELECT scan_call_lease FROM review_job WHERE id = ?", UUID.class, job.getId());
    }
}
