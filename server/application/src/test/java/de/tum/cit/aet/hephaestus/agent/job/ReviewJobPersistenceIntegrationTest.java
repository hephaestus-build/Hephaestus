package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEvent;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEventRepository;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageProvenance;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
@DisplayName("Stale RUNNING reaper over PostgreSQL Integration")
class ReviewJobPersistenceIntegrationTest extends BaseIntegrationTest {

    private ReviewJobZombieSweeper sweeper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkerRegistryRepository workerRegistryRepository;

    @Autowired
    private AgentProperties agentProperties;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ReviewJobLifecycleService lifecycleService;

    @Autowired
    private ReviewAttemptUsageService attemptUsage;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ReviewJobRepository jobRepository;

    @Autowired
    private ReviewRuntimeRepository runtime;

    @Autowired
    private LlmUsageEventRepository usageEventRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private LlmConnectionRepository connectionRepository;

    @Autowired
    private LlmModelRepository modelRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PracticeVersionService versions;

    private Workspace workspace;
    private LlmModel instanceModel;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        sweeper = new ReviewJobZombieSweeper(
                jobRepository,
                workerRegistryRepository,
                objectMapper,
                transactionTemplate,
                attemptUsage,
                meterRegistry,
                new ReviewJobTelemetry(meterRegistry, Tracer.NOOP),
                new ReviewJobRecovery(jobRepository, agentProperties, objectMapper, attemptUsage, Optional.empty()));
        workspace = workspaceRepository.save(TestEntities.activeWorkspace("stale-reap-ws"));
        LlmConnection connection = connectionRepository.save(LlmCatalogTestFixtures.connection("stale-reap"));
        instanceModel =
                modelRepository.save(LlmCatalogTestFixtures.model(connection, "stale-reap-model", "test-model"));
    }

    @Test
    void runtimeReadsKeepReviewWorkspaceAndPricingBoundaries() {
        UUID id = staleRunningJob(readableSnapshot(), withProxyUsage());
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
        sweeper.reapStaleRunningJobs();
        assertThat(runtime.unsettledJobIds(workspace.getId(), job.getReviewId()))
                .containsExactly(id);
        assertThat(runtime.unsettledJobIds(other.getId(), job.getReviewId())).isEmpty();
        assertThat(runtime.usage(workspace.getId(), job.getReviewId()))
                .singleElement()
                .satisfies(usage -> {
                    assertThat(usage.jobId()).isEqualTo(id);
                    assertThat(usage.calls()).isEqualTo(3);
                    assertThat(usage.inputTokens()).isEqualTo(900);
                    assertThat(usage.outputTokens()).isEqualTo(400);
                    assertThat(usage.costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
                    assertThat(usage.pricingState()).isEqualTo(UsagePricingState.NO_CHARGE);
                });
        assertThat(runtime.usage(other.getId(), job.getReviewId())).isEmpty();
    }

    @Test
    void captureWritesRequireTheWorkspaceWorkerAndCurrentRunningAttempt() {
        UUID id = staleRunningJob(readableSnapshot(), withProxyUsage());
        var job = jobRepository.findById(id).orElseThrow();
        var stamp = objectMapper.createObjectNode().put("inputsDigest", "a".repeat(64));
        var other = workspaceRepository.save(TestEntities.activeWorkspace("other-capture-ws"));
        transactionTemplate.executeWithoutResult(status -> {
            assertThat(jobRepository.updateCapture(id, other.getId(), job.getWorkerId(), job.getAttempt(), stamp))
                    .isZero();
            assertThat(jobRepository.updateCapture(id, workspace.getId(), "another-worker", job.getAttempt(), stamp))
                    .isZero();
            assertThat(jobRepository.updateCapture(
                            id, workspace.getId(), job.getWorkerId(), job.getAttempt() + 1, stamp))
                    .isZero();
            assertThat(jobRepository.updateCapture(id, workspace.getId(), job.getWorkerId(), job.getAttempt(), stamp))
                    .isEqualTo(1);
        });
        assertThat(jobRepository.findById(id).orElseThrow().getCapture()).isEqualTo(stamp);
    }

    @Test
    @DisplayName("a genuinely stale RUNNING job reaches TIMED_OUT and its ledger event is committed with it")
    void staleRunningJobIsTimedOutAndBilled() {
        // Past the work timeout, upload grace, and stale-job safety buffer.
        UUID jobId = staleRunningJob(readableSnapshot(), withProxyUsage());

        sweeper.reapStaleRunningJobs();

        ReviewJob reaped = jobRepository.findById(jobId).orElseThrow();
        assertThat(reaped.getStatus()).isEqualTo(ReviewJobStatus.TIMED_OUT);
        assertThat(reaped.getErrorMessage()).contains("exceeded its timeout");

        LlmUsageEvent event = onlyUsageEvent(jobId);
        assertThat(event.getSourceId()).isEqualTo(jobId);
        assertThat(event.getSourceType()).isEqualTo(UsageSourceType.REVIEW_JOB);
        assertThat(event.getWorkspaceId()).isEqualTo(workspace.getId());
        assertThat(event.getInputTokens()).isEqualTo(900L);
        assertThat(event.getOutputTokens()).isEqualTo(400L);
        assertThat(event.getTotalCalls()).isEqualTo(3);
        // NO_CHARGE is a declared price, so this is confirmed $0 — not unpriced.
        assertThat(event.getPricingState()).isEqualTo(UsagePricingState.NO_CHARGE);
    }

    @Test
    @DisplayName("a job whose snapshot this server cannot read still terminalises, billed UNPRICED")
    void unreadableSnapshotStillTerminalisesAndDoesNotWedge() {
        UUID jobId = staleRunningJob(snapshotFromTheFuture(), withProxyUsage());

        sweeper.reapStaleRunningJobs();

        ReviewJob reaped = jobRepository.findById(jobId).orElseThrow();
        assertThat(reaped.getStatus())
                .as("an unreadable price must not cost the job its exit from RUNNING")
                .isEqualTo(ReviewJobStatus.TIMED_OUT);

        LlmUsageEvent event = onlyUsageEvent(jobId);
        assertThat(event.getSourceId()).isEqualTo(jobId);
        assertThat(event.getPricingState()).isEqualTo(UsagePricingState.UNPRICED);
        assertThat(event.getCostUsd()).isNull();
        assertThat(event.getModel()).isNull();
        assertThat(event.getInputTokens()).isEqualTo(900L);

        // And the slot is genuinely released: a second sweep finds nothing left to reap, which is the
        // difference between "recovered" and "wedged in a 2-minute retry loop forever".
        sweeper.reapStaleRunningJobs();
        assertThat(usageEventRepository.findByWorkspaceIdAndSourceTypeAndSourceIdOrderBySourceAttemptAsc(
                        workspace.getId(), UsageSourceType.REVIEW_JOB, jobId))
                .hasSize(1);
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.TIMED_OUT);
    }

    @Test
    @DisplayName("a reaped attempt bills each model for practice scripts at its slot's frozen price and purse")
    void shouldAppendAPracticeScriptLedgerRowPerKindWhenAStaleJobIsReaped() {
        UUID jobId = staleRunningJob(practiceScriptSnapshot().toJson(objectMapper), withProxyUsage());
        practiceScriptCalls(jobId);

        sweeper.reapStaleRunningJobs();

        assertPracticeScriptLedgerRows(jobId);
    }

    @Test
    @DisplayName(
            "a cancelled attempt bills each model for practice scripts once over its practices, at its slot's price")
    void shouldAppendAPracticeScriptLedgerRowPerKindWhenARunningJobIsCancelled() {
        UUID jobId = staleRunningJob(practiceScriptSnapshot().toJson(objectMapper), withProxyUsage());
        practiceScriptCalls(jobId);

        lifecycleService.cancel(workspace.getId(), jobId);

        assertPracticeScriptLedgerRows(jobId);
        assertThat(jobRepository.findById(jobId))
                .get()
                .extracting(ReviewJob::getStatus, ReviewJob::getCancellationReason)
                .containsExactly(ReviewJobStatus.CANCELLED, ReviewJobCancellationReason.CANCELLED_BY_REQUESTER);
    }

    @Test
    void shouldCancelAQueuedJob() {
        UUID queuedId = staleRunningJob(snapshot().toJson(objectMapper), withProxyUsage());
        transactionTemplate.executeWithoutResult(transaction -> {
            var queued = jobRepository.findById(queuedId).orElseThrow();
            queued.setStatus(ReviewJobStatus.QUEUED);
            queued.setStartedAt(null);
            queued.setExecutionStartedAt(null);
            queued.setWorkerId(null);
            jobRepository.saveAndFlush(queued);
        });
        lifecycleService.cancel(workspace.getId(), queuedId);
        assertThat(jobRepository.findById(queuedId))
                .get()
                .extracting(ReviewJob::getStatus, ReviewJob::getCancellationReason)
                .containsExactly(ReviewJobStatus.CANCELLED, ReviewJobCancellationReason.CANCELLED_BY_REQUESTER);
    }

    @Test
    void shouldKeepACompletedJobWhenCancellationLosesTheTransition() {
        UUID completedId = staleRunningJob(snapshot().toJson(objectMapper), withProxyUsage());
        transactionTemplate.executeWithoutResult(transaction -> {
            var completed = jobRepository.findById(completedId).orElseThrow();
            completed.setStatus(ReviewJobStatus.COMPLETED);
            jobRepository.saveAndFlush(completed);
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

    private void practiceScriptCalls(UUID jobId) {
        for (String slug : List.of("comment-quality", "test-coverage")) {
            var kind = new ItemKind("scm.pull_request");
            versions.create(new PracticeVersionService.NewPractice(
                    workspace,
                    slug,
                    kind,
                    DefinitionTestFixtures.definition(kind, slug, "Review the work.", null),
                    null,
                    0,
                    null));
        }
        transactionTemplate.executeWithoutResult(tx -> {
            practiceScriptCall(jobId, ModelKind.DECISION, "comment-quality", 300_000, 50_000);
            practiceScriptCall(jobId, ModelKind.DECISION, "test-coverage", 200_000, 50_000);
            practiceScriptCall(jobId, ModelKind.EMBEDDING, "comment-quality", 1_000, 0);
            practiceScriptCall(jobId, ModelKind.RERANKING, "comment-quality", 0, 0);
            practiceScriptCall(jobId, ModelKind.CHAT, "comment-quality", 70, 20);
        });
    }

    private void practiceScriptCall(UUID jobId, ModelKind kind, String practice, long input, long output) {
        jobRepository.accumulatePracticeScriptUsage(
                jobId, 0, new PracticeScriptCallUsage(kind, practice, null, input, output, 0, 0, 0));
    }

    private void assertPracticeScriptLedgerRows(UUID jobId) {
        List<LlmUsageEvent> rows =
                usageEventRepository.findByWorkspaceIdAndSourceTypeInAndSourceIdOrderBySourceAttemptAsc(
                        workspace.getId(),
                        List.of(
                                UsageSourceType.REVIEW_JOB,
                                UsageSourceType.PRACTICE_SCRIPT_DECISION,
                                UsageSourceType.PRACTICE_SCRIPT_EMBEDDING,
                                UsageSourceType.PRACTICE_SCRIPT_RERANKING),
                        jobId);
        assertThat(rows)
                .extracting(LlmUsageEvent::getSourceType)
                .containsExactlyInAnyOrder(
                        UsageSourceType.REVIEW_JOB,
                        UsageSourceType.PRACTICE_SCRIPT_DECISION,
                        UsageSourceType.PRACTICE_SCRIPT_EMBEDDING,
                        UsageSourceType.PRACTICE_SCRIPT_RERANKING);

        LlmUsageEvent decision = row(rows, UsageSourceType.PRACTICE_SCRIPT_DECISION);
        assertThat(decision.getSourceAttempt()).isZero();
        assertThat(decision.getModel()).isEqualTo("decision-upstream");
        assertThat(decision.getInputTokens()).isEqualTo(500_000L);
        assertThat(decision.getOutputTokens()).isEqualTo(100_000L);
        assertThat(decision.getTotalCalls()).isEqualTo(2);
        assertThat(decision.getFundingSource()).isEqualTo(UsageFundingSource.WORKSPACE);
        assertThat(decision.getPricingState()).isEqualTo(UsagePricingState.PRICED);
        // 0.5M input at $2 plus 0.1M output at $8.
        assertThat(decision.getCostUsd()).isEqualByComparingTo("1.8");
        assertThat(decision.getUsageProvenance()).isEqualTo(UsageProvenance.PROXY);

        LlmUsageEvent embedding = row(rows, UsageSourceType.PRACTICE_SCRIPT_EMBEDDING);
        assertThat(embedding.getModel()).isEqualTo("embedding-upstream");
        assertThat(embedding.getInputTokens()).isEqualTo(1_000L);
        assertThat(embedding.getPricingState())
                .as("a slot admitted without a price is billed UNPRICED, never at an invented rate")
                .isEqualTo(UsagePricingState.UNPRICED);
        assertThat(embedding.getCostUsd()).isNull();

        LlmUsageEvent reranking = row(rows, UsageSourceType.PRACTICE_SCRIPT_RERANKING);
        assertThat(reranking.getTotalCalls()).isEqualTo(1);
        assertThat(reranking.getPricingState())
                .as("a model without metered cost is a confirmed zero, even when the provider reports no tokens")
                .isEqualTo(UsagePricingState.NO_CHARGE);
        assertThat(reranking.getCostUsd()).isEqualByComparingTo("0");
    }

    private static LlmUsageEvent row(List<LlmUsageEvent> rows, UsageSourceType sourceType) {
        return rows.stream()
                .filter(event -> event.getSourceType() == sourceType)
                .findFirst()
                .orElseThrow();
    }

    private ConfigSnapshot practiceScriptSnapshot() {
        Map<ModelKind, FrozenModel> slots = new EnumMap<>(ModelKind.class);
        slots.put(
                ModelKind.DECISION,
                slot(
                        "openai-decisions",
                        "decision-upstream",
                        new LlmPriceSnapshot(
                                UsageFundingSource.WORKSPACE,
                                UsagePricingState.PRICED,
                                null,
                                null,
                                new BigDecimal("2"),
                                new BigDecimal("8"),
                                null,
                                null)));
        slots.put(ModelKind.EMBEDDING, slot("openai-embeddings", "embedding-upstream", null));
        slots.put(
                ModelKind.RERANKING,
                slot(
                        "cohere-rerank",
                        "reranking-upstream",
                        new LlmPriceSnapshot(
                                UsageFundingSource.INSTANCE,
                                UsagePricingState.NO_CHARGE,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)));
        return snapshot().withPracticeScript(slots);
    }

    private FrozenModel slot(String protocol, String upstreamModelId, @Nullable LlmPriceSnapshot price) {
        return new FrozenModel(
                LlmApiProtocol.parse(protocol).orElseThrow(),
                "https://models.example.com/v1",
                upstreamModelId,
                price == null ? UsageFundingSource.INSTANCE : price.fundingSource(),
                instanceModel.getConnection().getId(),
                instanceModel.getId(),
                workspace.getId(),
                null,
                null,
                price);
    }

    private LlmUsageEvent onlyUsageEvent(UUID jobId) {
        List<LlmUsageEvent> events =
                usageEventRepository.findByWorkspaceIdAndSourceTypeAndSourceIdOrderBySourceAttemptAsc(
                        workspace.getId(), UsageSourceType.REVIEW_JOB, jobId);
        assertThat(events).as("the reap must append exactly one ledger event").hasSize(1);
        return events.getFirst();
    }

    private static ReviewJob withProxyUsage() {
        ReviewJob job = new ReviewJob();
        job.setLlmCalls(3);
        job.setLlmInputTokens(900);
        job.setLlmOutputTokens(400);
        job.setLlmReasoningTokens(0);
        job.setLlmCacheReadTokens(0);
        job.setLlmCacheWriteTokens(0);
        return job;
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
                                null),
                        null,
                        null,
                        600,
                        false,
                        null)
                .withPriceSnapshot(new LlmPriceSnapshot(
                        UsageFundingSource.INSTANCE, UsagePricingState.NO_CHARGE, null, null, null, null, null, null));
    }

    private UUID staleRunningJob(JsonNode configSnapshot, ReviewJob usage) {
        ReviewJob job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());

        job.setStatus(ReviewJobStatus.RUNNING);
        job.setConfigSnapshot(configSnapshot);
        job.setWorkerId("dead-replica");
        job.setStartedAt(Instant.now().minus(Duration.ofMinutes(30)));
        // Non-null executionStartedAt is what makes this attempt billable: it got past preparation and
        // actually ran, so its spend has to be accounted for.
        job.setExecutionStartedAt(Instant.now().minus(Duration.ofMinutes(29)));
        job.setLlmCalls(usage.getLlmCalls());
        job.setLlmInputTokens(usage.getLlmInputTokens());
        job.setLlmOutputTokens(usage.getLlmOutputTokens());
        job.setLlmReasoningTokens(usage.getLlmReasoningTokens());
        job.setLlmCacheReadTokens(usage.getLlmCacheReadTokens());
        job.setLlmCacheWriteTokens(usage.getLlmCacheWriteTokens());
        return jobRepository
                .saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job))
                .getId();
    }
}
