package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.ModelKind;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEvent;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEventRepository;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.agent.usage.PricingState;
import de.tum.cit.aet.hephaestus.agent.usage.UsageProvenance;
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
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The absolute-timeout reaper against REAL Postgres. The unit test for this path mocks both the
 * {@code TransactionTemplate} and the {@link de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder},
 * so it cannot see the two things that matter here: a throw in the ledger append genuinely rolls the
 * state transition back, and the recorder's {@code @Transactional(propagation = MANDATORY)} is
 * genuinely satisfied by the reaper's own per-job transaction.
 */
@DisplayName("Stale RUNNING reaper over PostgreSQL Integration")
class AgentJobStaleReapIntegrationTest extends BaseIntegrationTest {

    private AgentJobZombieSweeper sweeper;

    @Autowired
    private WorkerRegistryRepository workerRegistryRepository;

    @Autowired
    private AgentProperties agentProperties;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private AgentJobLifecycleService lifecycleService;

    @Autowired
    private LlmUsageRecorder usageRecorder;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private AgentJobRepository jobRepository;

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

    private Workspace workspace;
    private LlmModel instanceModel;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        sweeper = new AgentJobZombieSweeper(
                jobRepository,
                workerRegistryRepository,
                agentProperties,
                objectMapper,
                transactionTemplate,
                lifecycleService,
                usageRecorder,
                meterRegistry,
                new AgentJobTelemetry(meterRegistry, Tracer.NOOP));
        workspace = workspaceRepository.save(TestEntities.activeWorkspace("stale-reap-ws"));
        LlmConnection connection = connectionRepository.save(LlmCatalogTestFixtures.connection("stale-reap"));
        instanceModel =
                modelRepository.save(LlmCatalogTestFixtures.model(connection, "stale-reap-model", "test-model"));
    }

    @Test
    @DisplayName("a genuinely stale RUNNING job reaches TIMED_OUT and its ledger event is committed with it")
    void staleRunningJobIsTimedOutAndBilled() {
        // Past the work timeout, upload grace, and stale-job safety buffer.
        UUID jobId = staleRunningJob(readableSnapshot(), withProxyUsage());

        sweeper.reapStaleRunningJobs();

        AgentJob reaped = jobRepository.findById(jobId).orElseThrow();
        assertThat(reaped.getStatus()).isEqualTo(AgentJobStatus.TIMED_OUT);
        assertThat(reaped.getErrorMessage()).contains("exceeded its timeout");

        LlmUsageEvent event = onlyUsageEvent();
        assertThat(event.getSourceId()).isEqualTo(jobId);
        assertThat(event.getSourceType()).isEqualTo(LlmUsageSourceType.AGENT_JOB);
        assertThat(event.getWorkspace().getId()).isEqualTo(workspace.getId());
        assertThat(event.getInputTokens()).isEqualTo(900L);
        assertThat(event.getOutputTokens()).isEqualTo(400L);
        assertThat(event.getTotalCalls()).isEqualTo(3);
        // NO_CHARGE is a declared price, so this is confirmed $0 — not unpriced.
        assertThat(event.getPricingState()).isEqualTo(PricingState.NO_CHARGE);
    }

    @Test
    @DisplayName("a job whose snapshot this server cannot read still terminalises, billed UNPRICED")
    void unreadableSnapshotStillTerminalisesAndDoesNotWedge() {
        UUID jobId = staleRunningJob(snapshotFromTheFuture(), withProxyUsage());

        sweeper.reapStaleRunningJobs();

        AgentJob reaped = jobRepository.findById(jobId).orElseThrow();
        assertThat(reaped.getStatus())
                .as("an unreadable price must not cost the job its exit from RUNNING")
                .isEqualTo(AgentJobStatus.TIMED_OUT);

        LlmUsageEvent event = onlyUsageEvent();
        assertThat(event.getSourceId()).isEqualTo(jobId);
        assertThat(event.getPricingState()).isEqualTo(PricingState.UNPRICED);
        assertThat(event.getCostUsd()).isNull();
        assertThat(event.getModel()).isNull();
        assertThat(event.getInputTokens()).isEqualTo(900L);

        // And the slot is genuinely released: a second sweep finds nothing left to reap, which is the
        // difference between "recovered" and "wedged in a 2-minute retry loop forever".
        sweeper.reapStaleRunningJobs();
        assertThat(usageEventRepository.findAll()).hasSize(1);
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus()).isEqualTo(AgentJobStatus.TIMED_OUT);
    }

    /**
     * The reaper appends one row per non-chat precompute model next to the review's row, at the price
     * and from the purse that the slot froze. The chat row only bounds spend: the review's row bills it.
     */
    @Test
    @DisplayName("a reaped attempt bills each precompute model at its slot's frozen price and purse")
    void shouldAppendAPrecomputeLedgerRowPerKindWhenAStaleJobIsReaped() {
        UUID jobId = staleRunningJob(precomputeSnapshot().toJson(objectMapper), withProxyUsage());
        precomputeCalls(jobId);

        sweeper.reapStaleRunningJobs();

        assertPrecomputeLedgerRows(jobId);
    }

    @Test
    @DisplayName("a cancelled attempt bills each precompute model once over its practices, at its slot's price")
    void shouldAppendAPrecomputeLedgerRowPerKindWhenARunningJobIsCancelled() {
        UUID jobId = staleRunningJob(precomputeSnapshot().toJson(objectMapper), withProxyUsage());
        precomputeCalls(jobId);

        lifecycleService.cancel(workspace.getId(), jobId);

        assertPrecomputeLedgerRows(jobId);
    }

    private void precomputeCalls(UUID jobId) {
        transactionTemplate.executeWithoutResult(tx -> {
            precomputeCall(jobId, ModelKind.DECISION, "comment-quality", 300_000, 50_000);
            precomputeCall(jobId, ModelKind.DECISION, "test-coverage", 200_000, 50_000);
            precomputeCall(jobId, ModelKind.EMBEDDING, "comment-quality", 1_000, 0);
            precomputeCall(jobId, ModelKind.RERANKING, "comment-quality", 0, 0);
            precomputeCall(jobId, ModelKind.CHAT, "comment-quality", 70, 20);
        });
    }

    private void precomputeCall(UUID jobId, ModelKind kind, String practice, long input, long output) {
        jobRepository.accumulatePrecomputeUsage(jobId, 0, new PrecomputeCallUsage(kind, practice, null, input, output));
    }

    private void assertPrecomputeLedgerRows(UUID jobId) {
        List<LlmUsageEvent> rows = usageEventRepository.findAll().stream()
                .filter(event -> event.getSourceId().equals(jobId))
                .toList();
        assertThat(rows)
                .extracting(LlmUsageEvent::getSourceType)
                .containsExactlyInAnyOrder(
                        LlmUsageSourceType.AGENT_JOB,
                        LlmUsageSourceType.PRECOMPUTE_DECISION,
                        LlmUsageSourceType.PRECOMPUTE_EMBEDDING,
                        LlmUsageSourceType.PRECOMPUTE_RERANKING);

        LlmUsageEvent decision = row(rows, LlmUsageSourceType.PRECOMPUTE_DECISION);
        assertThat(decision.getSourceAttempt()).isZero();
        assertThat(decision.getModel()).isEqualTo("decision-upstream");
        assertThat(decision.getInputTokens()).isEqualTo(500_000L);
        assertThat(decision.getOutputTokens()).isEqualTo(100_000L);
        assertThat(decision.getTotalCalls()).isEqualTo(2);
        assertThat(decision.getFundingSource()).isEqualTo(FundingSource.WORKSPACE);
        assertThat(decision.getPricingState()).isEqualTo(PricingState.PRICED);
        // 0.5M input at $2 plus 0.1M output at $8.
        assertThat(decision.getCostUsd()).isEqualByComparingTo("1.8");
        assertThat(decision.getUsageProvenance()).isEqualTo(UsageProvenance.PROXY);

        LlmUsageEvent embedding = row(rows, LlmUsageSourceType.PRECOMPUTE_EMBEDDING);
        assertThat(embedding.getModel()).isEqualTo("embedding-upstream");
        assertThat(embedding.getInputTokens()).isEqualTo(1_000L);
        assertThat(embedding.getPricingState())
                .as("a slot admitted without a price is billed UNPRICED, never at an invented rate")
                .isEqualTo(PricingState.UNPRICED);
        assertThat(embedding.getCostUsd()).isNull();

        LlmUsageEvent reranking = row(rows, LlmUsageSourceType.PRECOMPUTE_RERANKING);
        assertThat(reranking.getTotalCalls()).isEqualTo(1);
        assertThat(reranking.getPricingState())
                .as("a model without metered cost is a confirmed zero, even when the provider reports no tokens")
                .isEqualTo(PricingState.NO_CHARGE);
        assertThat(reranking.getCostUsd()).isEqualByComparingTo("0");
    }

    private static LlmUsageEvent row(List<LlmUsageEvent> rows, LlmUsageSourceType sourceType) {
        return rows.stream()
                .filter(event -> event.getSourceType() == sourceType)
                .findFirst()
                .orElseThrow();
    }

    private ConfigSnapshot precomputeSnapshot() {
        Map<ModelKind, FrozenModel> slots = new EnumMap<>(ModelKind.class);
        slots.put(
                ModelKind.DECISION,
                slot(
                        "openai-decisions",
                        "decision-upstream",
                        new LlmPriceSnapshot(
                                FundingSource.WORKSPACE,
                                PricingState.PRICED,
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
                                FundingSource.INSTANCE, PricingState.NO_CHARGE, null, null, null, null, null, null)));
        return snapshot().withPrecompute(slots);
    }

    private FrozenModel slot(String protocol, String upstreamModelId, @Nullable LlmPriceSnapshot price) {
        return new FrozenModel(
                protocol,
                "https://models.example.com/v1",
                upstreamModelId,
                price == null ? FundingSource.INSTANCE : price.fundingSource(),
                instanceModel.getConnection().getId(),
                instanceModel.getId(),
                workspace.getId(),
                null,
                null,
                price);
    }

    private LlmUsageEvent onlyUsageEvent() {
        List<LlmUsageEvent> events = usageEventRepository.findAll();
        assertThat(events).as("the reap must append exactly one ledger event").hasSize(1);
        return events.getFirst();
    }

    private static AgentJob withProxyUsage() {
        AgentJob job = new AgentJob();
        job.setLlmTotalCalls(3);
        job.setLlmTotalInputTokens(900);
        job.setLlmTotalOutputTokens(400);
        job.setLlmTotalReasoningTokens(0);
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
                        FundingSource.INSTANCE, PricingState.NO_CHARGE, null, null, null, null, null, null));
    }

    private UUID staleRunningJob(JsonNode configSnapshot, AgentJob usage) {
        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setStatus(AgentJobStatus.RUNNING);
        job.setConfigSnapshot(configSnapshot);
        job.setWorkerId("dead-replica");
        job.setStartedAt(Instant.now().minus(Duration.ofMinutes(30)));
        // Non-null executionStartedAt is what makes this attempt billable: it got past preparation and
        // actually ran, so its spend has to be accounted for.
        job.setExecutionStartedAt(Instant.now().minus(Duration.ofMinutes(29)));
        job.setLlmTotalCalls(usage.getLlmTotalCalls());
        job.setLlmTotalInputTokens(usage.getLlmTotalInputTokens());
        job.setLlmTotalOutputTokens(usage.getLlmTotalOutputTokens());
        job.setLlmTotalReasoningTokens(usage.getLlmTotalReasoningTokens());
        job.setLlmCacheReadTokens(usage.getLlmCacheReadTokens());
        job.setLlmCacheWriteTokens(usage.getLlmCacheWriteTokens());
        return jobRepository.saveAndFlush(job).getId();
    }
}
