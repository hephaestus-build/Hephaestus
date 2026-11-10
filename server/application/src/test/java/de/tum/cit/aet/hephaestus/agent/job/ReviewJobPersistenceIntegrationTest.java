package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunReaper;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// Real transactions must roll back the state change when ledger append fails and satisfy MANDATORY propagation.
@DisplayName("Lost review attempts over PostgreSQL Integration")
class ReviewJobPersistenceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AgentRunReaper reaper;

    @Autowired
    private AgentCapacityProperties capacity;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ReviewJobLifecycleService lifecycleService;

    @Autowired
    private ReviewJobRepository jobRepository;

    @Autowired
    private ReviewRuntimeRepository runtime;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private LlmConnectionRepository connectionRepository;

    @Autowired
    private LlmModelRepository modelRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Workspace workspace;
    private LlmModel instanceModel;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        workspace = workspaceRepository.save(TestEntities.activeWorkspace("stale-reap-ws"));
        LlmConnection connection = connectionRepository.save(LlmCatalogTestFixtures.connection("stale-reap"));
        instanceModel =
                modelRepository.save(LlmCatalogTestFixtures.model(connection, "stale-reap-model", "test-model"));
    }

    @Test
    void runtimeReadsKeepReviewWorkspaceAndPricingBoundaries() {
        // Every retry is spent, so losing this attempt ends the job and leaves it to settle.
        UUID id = staleRunningJob(readableSnapshot(), capacity.reviewRetries());
        var job = jobRepository.findById(id).orElseThrow();
        var other = workspaceRepository.save(TestEntities.activeWorkspace("other-runtime-ws"));
        var now = Instant.now();
        assertThat(runtime.queueCounts(workspace.getId(), now).running()).isEqualTo(1);
        assertThat(runtime.queueCounts(other.getId(), now).running()).isZero();
        assertThat(runtime.timingJobs(workspace.getId(), List.of(job.getReviewId())))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.reviewId()).isEqualTo(job.getReviewId());
                    assertThat(row.status()).isEqualTo(ReviewJobStatus.RUNNING);
                    assertThat(row.completedAt()).isNull();
                });
        assertThat(runtime.timingJobs(other.getId(), List.of(job.getReviewId())))
                .isEmpty();
        assertThat(runtime.spent(workspace.getId(), job.getReviewId()))
                .isEqualTo(new ReviewRuntimeRepository.Spend(BigDecimal.ZERO, true));
        UUID call = openCall(id, epochOf(id));
        jdbc.update(
                "UPDATE llm_usage_event SET call_state='ENDED', input_tokens=900, output_tokens=400,"
                        + " usage_provenance='PROXY', measurement_verification='VERIFIED' WHERE id=?",
                call);
        reapLost(id);
        assertThat(runtime.unsettledJobIds(workspace.getId(), job.getReviewId()))
                .containsExactly(id);
        assertThat(runtime.unsettledJobIds(other.getId(), job.getReviewId())).isEmpty();
        assertThat(runtime.usage(workspace.getId(), job.getReviewId()))
                .singleElement()
                .satisfies(usage -> {
                    assertThat(usage.jobId()).isEqualTo(id);
                    assertThat(usage.calls()).isEqualTo(1);
                    assertThat(usage.inputTokens()).isEqualTo(900);
                    assertThat(usage.outputTokens()).isEqualTo(400);
                    assertThat(usage.costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
                    assertThat(usage.pricingState()).isEqualTo(UsagePricingState.NO_CHARGE);
                });
        assertThat(runtime.usage(other.getId(), job.getReviewId())).isEmpty();
    }

    @Test
    void captureWritesRequireTheWorkspaceWorkerAndCurrentRunningAttempt() {
        UUID id = staleRunningJob(readableSnapshot());
        var job = jobRepository.findById(id).orElseThrow();
        var stamp = objectMapper.createObjectNode().put("inputsDigest", "a".repeat(64));
        var other = workspaceRepository.save(TestEntities.activeWorkspace("other-capture-ws"));
        transactionTemplate.executeWithoutResult(status -> {
            assertThat(jobRepository.updateCapture(id, other.getId(), job.getRunId(), job.getAttempt(), stamp))
                    .isZero();
            assertThat(jobRepository.updateCapture(id, workspace.getId(), UUID.randomUUID(), job.getAttempt(), stamp))
                    .isZero();
            assertThat(jobRepository.updateCapture(id, workspace.getId(), job.getRunId(), job.getAttempt() + 1, stamp))
                    .isZero();
            assertThat(jobRepository.updateCapture(id, workspace.getId(), job.getRunId(), job.getAttempt(), stamp))
                    .isEqualTo(1);
        });
        assertThat(jobRepository.findById(id).orElseThrow().getCapture()).isEqualTo(stamp);
    }

    @Test
    @DisplayName("the reaper's take-back settles the calls that the lost claim left open, and no other claim's")
    void shouldSettleTheOpenCallsOfTheLostClaimOnlyWhenTheReaperTakesItBack() {
        UUID jobId = staleRunningJob(readableSnapshot());
        int lostEpoch = epochOf(jobId);
        UUID lost = openCall(jobId, lostEpoch);
        // A row of another claim of the same run, which this take-back must leave alone.
        UUID other = openCall(jobId, lostEpoch + 1);

        reapLost(jobId);

        ReviewJob reaped = jobRepository.findById(jobId).orElseThrow();
        assertThat(reaped.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(reaped.spentAttempts()).isEqualTo(1);
        assertThat(callState(lost)).isEqualTo("SETTLED:UNVERIFIABLE:UNPRICED");
        assertThat(callState(other)).isEqualTo("OPEN:UNKNOWN:NO_CHARGE");
    }

    @Test
    @DisplayName("an attempt whose snapshot this server cannot read is still taken back")
    void unreadableSnapshotStillTerminalisesAndDoesNotWedge() {
        UUID jobId = staleRunningJob(snapshotFromTheFuture());

        reapLost(jobId);

        ReviewJob reaped = jobRepository.findById(jobId).orElseThrow();
        assertThat(reaped.getStatus())
                .as("an unreadable price must not keep the attempt holding its job")
                .isEqualTo(ReviewJobStatus.QUEUED);

        // And the lease is genuinely released: a second pass finds nothing left to take back, which is the
        // difference between "recovered" and "wedged in a retry loop forever".
        reaper.reap();
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(jobRepository.findById(jobId).orElseThrow().spentAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("cancelling a running job settles every call its run left open")
    void shouldSettleTheOpenCallsWhenARunningJobIsCancelled() {
        UUID jobId = staleRunningJob(readableSnapshot());
        UUID open = openCall(jobId, epochOf(jobId));

        lifecycleService.cancel(workspace.getId(), jobId);

        assertThat(callState(open)).isEqualTo("SETTLED:UNVERIFIABLE:UNPRICED");
        assertThat(jobRepository.findById(jobId))
                .get()
                .extracting(ReviewJob::getStatus, ReviewJob::getCancellationReason)
                .containsExactly(ReviewJobStatus.CANCELLED, ReviewJobCancellationReason.CANCELLED_BY_REQUESTER);
    }

    private int epochOf(UUID jobId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT r.lease_epoch FROM agent_run r JOIN review_job j ON j.run_id = r.id WHERE j.id = ?",
                Integer.class,
                jobId));
    }

    /** A model call of the job that is still at its provider, under claim {@code epoch} of the job's run. */
    private UUID openCall(UUID jobId, int epoch) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO llm_usage_event (id, workspace_id, run_id, lease_epoch, call_state, job_type, source_type,
                    source_id, source_attempt, review_id, model_kind, pricing_state, funding_source, cost_usd,
                    started_at, occurred_at)
                SELECT ?, j.workspace_id, j.run_id, ?, 'OPEN', 'PRACTICE_REVIEW', 'REVIEW_JOB', j.id, ? - 1,
                    j.review_id, 'CHAT', 'NO_CHARGE', 'INSTANCE', 0, now(), now()
                FROM review_job j WHERE j.id = ?
                """, id, epoch, epoch, jobId);
        return id;
    }

    private String callState(UUID call) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT call_state || ':' || measurement_verification || ':' || pricing_state FROM llm_usage_event"
                        + " WHERE id = ?",
                String.class,
                call));
    }

    @Test
    void shouldCancelAQueuedJob() {
        UUID queuedId = staleRunningJob(snapshot().toJson(objectMapper));
        transactionTemplate.executeWithoutResult(transaction -> {
            var queued = jobRepository.findById(queuedId).orElseThrow();
            queued.setStatus(ReviewJobStatus.QUEUED);
            queued.setStartedAt(null);
            queued.setExecutionStartedAt(null);
            jobRepository.saveAndFlush(queued);
            RuntimeJobTestData.persistRun(jdbc, queued);
        });
        lifecycleService.cancel(workspace.getId(), queuedId);
        assertThat(jobRepository.findById(queuedId))
                .get()
                .extracting(ReviewJob::getStatus, ReviewJob::getCancellationReason)
                .containsExactly(ReviewJobStatus.CANCELLED, ReviewJobCancellationReason.CANCELLED_BY_REQUESTER);
    }

    @Test
    void shouldKeepACompletedJobWhenCancellationLosesTheTransition() {
        UUID completedId = staleRunningJob(snapshot().toJson(objectMapper));
        transactionTemplate.executeWithoutResult(transaction -> {
            var completed = jobRepository.findById(completedId).orElseThrow();
            completed.setStatus(ReviewJobStatus.COMPLETED);
            completed.setCompletedAt(Instant.now());
            jobRepository.saveAndFlush(completed);
            RuntimeJobTestData.persistRun(jdbc, completed);
        });
        Integer updated = transactionTemplate.execute(transaction -> jobRepository.transitionToCancelled(
                completedId,
                Instant.now(),
                null,
                ReviewJobCancellationReason.CANCELLED_BY_REQUESTER,
                Set.of(ReviewJobStatus.QUEUED, ReviewJobStatus.RUNNING)));
        assertThat(updated).isZero();
        assertThat(jobRepository.findById(completedId))
                .get()
                .extracting(ReviewJob::getStatus, ReviewJob::getCancellationReason)
                .containsExactly(ReviewJobStatus.COMPLETED, null);
    }

    /** The attempt's holder stopped renewing its run, and the reaper takes it back. */
    private void reapLost(UUID jobId) {
        RuntimeJobTestData.leaseUntil(
                jdbc, jobRepository.findById(jobId).orElseThrow(), Instant.now().minusSeconds(1));
        reaper.reap();
    }

    private JsonNode readableSnapshot() {
        return snapshot().toJson(objectMapper);
    }

    private JsonNode snapshotFromTheFuture() {
        ObjectNode node = (ObjectNode) snapshot().toJson(objectMapper);
        return node.put("schemaVersion", ConfigSnapshot.SCHEMA_VERSION + 1);
    }

    private ConfigSnapshot snapshot() {
        return new ConfigSnapshot(
                        ConfigSnapshot.SCHEMA_VERSION,
                        new FrozenModel(
                                LlmApiProtocol.OPENAI_COMPLETIONS,
                                "https://api.openai.com/v1",
                                "test-model",
                                UsageFundingSource.INSTANCE,
                                instanceModel.getConnection().getId(),
                                instanceModel.getId(),
                                workspace.getId(),
                                null,
                                null,
                                null,
                                false),
                        null,
                        null,
                        600)
                .withPriceSnapshot(new LlmPriceSnapshot(
                        UsageFundingSource.INSTANCE, UsagePricingState.NO_CHARGE, null, null, null, null, null, null));
    }

    private UUID staleRunningJob(JsonNode configSnapshot) {
        return staleRunningJob(configSnapshot, 0);
    }

    /** A running job whose run has spent {@code spent} retries, on its attempt of that number. */
    private UUID staleRunningJob(JsonNode configSnapshot, int spent) {
        ReviewJob job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());
        RuntimeJobTestData.spent(RuntimeJobTestData.attempt(job, spent), spent);

        job.setStatus(ReviewJobStatus.RUNNING);
        job.setConfigSnapshot(configSnapshot);
        job.setStartedAt(Instant.now().minus(Duration.ofMinutes(30)));
        // Non-null executionStartedAt is what makes this attempt billable: it got past preparation and
        // actually ran, so its spend has to be accounted for.
        job.setExecutionStartedAt(Instant.now().minus(Duration.ofMinutes(29)));
        return jobRepository
                .saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job))
                .getId();
    }
}
