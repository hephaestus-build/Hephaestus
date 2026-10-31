package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** The registration of an attempt's first model calls, through the production service on real PostgreSQL. */
@Tag("integration")
class ReviewAttemptUsageRegistrationIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final UUID LEASE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int JOBS = 40;
    /** The first calls of one attempt that arrive together. */
    private static final int CALLS = 6;

    private static final FrozenCharge CHAT = new FrozenCharge(
            "chat-a",
            new LlmPriceSnapshot(
                    UsageFundingSource.INSTANCE, UsagePricingState.NO_CHARGE, null, null, null, null, null, null));

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReviewJobRepository jobs;

    @Autowired
    private ReviewAttemptUsageService attemptUsage;

    @Test
    void shouldRegisterEveryConcurrentFirstCallOfAnAttemptOnOneMeasurement() throws Exception {
        Workspace workspace = createWorkspace(
                "usage-registration",
                "Usage registration",
                "usage-registration-org",
                AccountType.ORG,
                persistUser("usage-registration-owner"));
        List<ReviewJob> running = new ArrayList<>();
        for (int i = 0; i < JOBS; i++) running.add(runningJob(workspace));

        CyclicBarrier together = new CyclicBarrier(CALLS);
        var failures = new ConcurrentLinkedQueue<Throwable>();
        var refused = new ConcurrentLinkedQueue<ReviewJob>();
        ExecutorService callers = Executors.newFixedThreadPool(CALLS);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int s = 0; s < CALLS; s++) {
                done.add(callers.submit(() -> {
                    for (ReviewJob job : running) {
                        try {
                            together.await(30, TimeUnit.SECONDS);
                            if (!attemptUsage.registerCall(
                                    job.getId(), job.getWorkspaceId(), 0, LEASE, ModelKind.CHAT, CHAT)) {
                                refused.add(job);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw e;
                        } catch (Exception e) {
                            failures.add(e);
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> caller : done) caller.get(2, TimeUnit.MINUTES);
        } finally {
            callers.shutdownNow();
        }

        assertThat(failures).isEmpty();
        assertThat(refused).isEmpty();
        for (ReviewJob job : running) {
            assertThat(jdbc.queryForList(
                            "SELECT attempt || ':' || workspace_id || ':' || proxy_calls FROM review_job_attempt_usage"
                                    + " WHERE job_id = ?",
                            String.class,
                            job.getId()))
                    .containsExactly("0:" + workspace.getId() + ":0");
            assertThat(jdbc.queryForList(
                            "SELECT attempt || ':' || model_kind || ':' || model FROM review_job_attempt_charge"
                                    + " WHERE job_id = ?",
                            String.class,
                            job.getId()))
                    .containsExactly("0:CHAT:chat-a");
        }
    }

    private ReviewJob runningJob(Workspace workspace) {
        ReviewJob job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setLeaseId(LEASE);
        job.setLeasedUntil(Instant.now().plusSeconds(3600));
        job.setExecutionStartedAt(Instant.now());
        job.setConfigSnapshot(MAPPER.createObjectNode());
        job.prePersist();
        return jobs.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
    }
}
