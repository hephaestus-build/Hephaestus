package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.catalog.DataHandlingFacts;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmDataOperator;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPrice;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPriceRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.PricingMode;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures;
import de.tum.cit.aet.hephaestus.agent.handler.JobTypeHandlerRegistry;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobTypeHandler;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetDecision;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
import de.tum.cit.aet.hephaestus.integration.core.signal.PracticeReviewRefusalMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The terminal write against real PostgreSQL: a result jsonb cannot store ends the job once as FAILED with its usage,
 * and is neither retried nor delivered; a result it can store is kept exactly.
 */
class AgentJobTerminalStorageIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AgentJobRepository jobs;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private WorkspaceAgentBindingRepository bindings;

    @Autowired
    private LlmConnectionRepository connections;

    @Autowired
    private LlmModelRepository models;

    @Autowired
    private LlmModelPriceRepository prices;

    @Autowired
    private LlmModelResolver resolver;

    @Autowired
    private LlmAdmissionService admission;

    @Autowired
    private LlmUsageRecorder usageRecorder;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private AgentProperties properties;

    private final ReviewMemberAiPolicy policy = mock(ReviewMemberAiPolicy.class);
    private final SandboxManager sandbox = mock(SandboxManager.class);
    private final PracticePiAdapter runner = mock(PracticePiAdapter.class);
    private final JobTypeHandler handler = mock(JobTypeHandler.class);
    private Workspace workspace;
    private AgentJobExecutor executor;

    @BeforeEach
    void setUp() {
        workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("terminal-" + UUID.randomUUID()));
        doReturn(true).when(policy).permitsReview(anyLong(), any(), any());
        var binding = binding();
        when(policy.binding(anyLong(), any(), any())).thenReturn(Optional.of(binding));
        var registry = mock(JobTypeHandlerRegistry.class);
        when(registry.getHandler(AgentJobType.PULL_REQUEST_REVIEW)).thenReturn(handler);
        when(handler.prepareInputs(any()))
                .thenAnswer(invocation -> PreparedJobInputsFixtures.filesOnly(
                        Map.of("task.json", "{}".getBytes(StandardCharsets.UTF_8))));
        when(runner.buildSandboxSpec(any()))
                .thenReturn(new PracticeSandboxSpec(
                        "ghcr.io/agent:latest", List.of("/bin/agent"), Map.of(), Map.of(), "/output", null, null, "p"));
        var budgets = mock(LlmBudgetService.class);
        when(budgets.decide(anyLong())).thenReturn(LlmBudgetDecision.ALLOWED);
        // The claimed job runs on the calling thread, so each test reads the committed rows when it returns.
        var direct = mock(AsyncTaskExecutor.class);
        doAnswer(invocation -> {
                    invocation.<Runnable>getArgument(0).run();
                    return null;
                })
                .when(direct)
                .execute(any(Runnable.class));
        var metrics = new SimpleMeterRegistry();
        var issuer = mock(WorkerJwtIssuer.class);
        when(issuer.issueForJobUntil(any(), anyLong(), anyInt(), any())).thenReturn("test-job-token");
        executor = new AgentJobExecutor(
                properties,
                jobs,
                policy,
                registry,
                runner,
                issuer,
                sandbox,
                direct,
                transactions,
                mapper,
                metrics,
                new PracticeReviewRefusalMetrics(metrics),
                new AgentJobTelemetry(metrics, Tracer.NOOP),
                usageRecorder,
                budgets,
                admission,
                Optional.empty(),
                Optional.empty());
    }

    @Test
    @DisplayName("a result with a NUL in a value is not kept: the job fails once, books its usage and delivers nothing")
    void shouldFailOnceAndBookUsageWhenTheResultHasANulValue() {
        assertFailedOnceWithoutTheResult(Map.of("feedback", Map.of("summary", "before\u0000after")));
    }

    @Test
    @DisplayName("a NUL in an object key and an unpaired surrogate are refused the same way")
    void shouldFailOnceAndBookUsageWhenTheResultHasANulKeyOrALoneSurrogate() {
        assertFailedOnceWithoutTheResult(Map.of("feedback", Map.of("bad\u0000key", "value")));
        assertFailedOnceWithoutTheResult(Map.of("feedback", List.of("lone \uD83D surrogate")));
    }

    @Test
    @DisplayName("literal escape text, paired emoji and control characters are kept exactly")
    void shouldKeepAResultJsonbCanStoreExactly() {
        Map<String, Object> result = Map.of(
                "feedback",
                Map.of(
                        "summary",
                        "a literal \\u0000 escape, emoji 😀, a control \u0001 and nested \\\\uD800",
                        "a literal \\u0000 key",
                        List.of("tab\there")));
        AgentJob job = queued();
        stubRun(result, "transcript");

        executor.processJob(job.getId());

        AgentJob stored = jobs.findById(job.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AgentJobStatus.COMPLETED);
        assertThat(stored.getOutput()).isEqualTo(mapper.valueToTree(result));
        assertThat(stored.getContainerLogs()).isEqualTo("transcript");
        assertBooked(stored, 2, 100, 50, "PROXY");
    }

    @Test
    @DisplayName("usage the runner reported is what the refused result books, on the row and in the ledger")
    void shouldBookTheRunnersReportedUsageWhenTheResultIsNotKept() {
        AgentJob job = queued();
        var reported = new AgentResult.LlmUsage("provider-name", 300, 120, null, null, null, null, 3);
        when(runner.parseResult(any()))
                .thenReturn(
                        new AgentResult(true, Map.of("feedback", Map.of("summary", "before\u0000after")), reported));
        when(sandbox.execute(any()))
                .thenReturn(new SandboxResult(0, Map.of(), "transcript", false, Duration.ofSeconds(1)));

        executor.processJob(job.getId());

        AgentJob stored = jobs.findById(job.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AgentJobStatus.FAILED);
        assertThat(stored.getLlmTotalCalls()).isEqualTo(3);
        assertThat(stored.getLlmTotalInputTokens()).isEqualTo(300);
        assertThat(stored.getLlmTotalOutputTokens()).isEqualTo(120);
        assertBooked(stored, 3, 300, 120, "RUNNER");
    }

    @Test
    @DisplayName("a transcript with a NUL is replaced whole, and the valid result still completes")
    void shouldReplaceAnUnstorableTranscriptWholeAndKeepTheResult() {
        Map<String, Object> result = Map.of("review", "LGTM");
        AgentJob job = queued();
        stubRun(result, "start\u0000end");

        executor.processJob(job.getId());

        AgentJob stored = jobs.findById(job.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AgentJobStatus.COMPLETED);
        assertThat(stored.getOutput()).isEqualTo(mapper.valueToTree(result));
        assertThat(stored.getContainerLogs()).isEqualTo(AgentJobExecutor.UNSTORABLE_TRANSCRIPT);
    }

    @Test
    @DisplayName("a job no longer owned keeps none of the attempt's result and books nothing for it")
    void shouldWriteNothingWhenOwnershipWasLostBeforeTheTerminalWrite() {
        AgentJob job = queued();
        when(runner.parseResult(any()))
                .thenReturn(new AgentResult(true, Map.of("feedback", Map.of("summary", "before\u0000after"))));
        when(sandbox.execute(any())).thenAnswer(invocation -> {
            transactions.executeWithoutResult(status -> jobs.transitionStatus(
                    job.getId(), AgentJobStatus.CANCELLED, Instant.now(), "cancelled", Set.of(AgentJobStatus.RUNNING)));
            return new SandboxResult(0, Map.of(), "transcript", false, Duration.ofSeconds(1));
        });

        executor.processJob(job.getId());

        AgentJob stored = jobs.findById(job.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AgentJobStatus.CANCELLED);
        assertThat(stored.getOutput()).isNull();
        assertThat(usageRows(job.getId())).isZero();
        verify(handler, never()).deliver(any());
    }

    private void assertFailedOnceWithoutTheResult(Map<String, Object> result) {
        AgentJob job = queued();
        stubRun(result, "transcript");

        executor.processJob(job.getId());

        AgentJob stored = jobs.findById(job.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AgentJobStatus.FAILED);
        assertThat(stored.getErrorMessage()).isEqualTo(AgentJobExecutor.UNSTORABLE_RESULT_MESSAGE);
        var output = Objects.requireNonNull(stored.getOutput());
        assertThat(output.has("resultNotStored")).isTrue();
        assertThat(output.has("feedback")).isFalse();
        assertThat(stored.getRetryCount()).isZero();
        assertThat(stored.getDeliveryStatus()).isNull();
        assertBooked(stored, 2, 100, 50, "PROXY");
        verify(sandbox, times(1)).execute(any());
        verify(handler, never()).deliver(any());
        clearInvocations(sandbox, handler);
    }

    private void stubRun(Map<String, Object> result, String transcript) {
        when(runner.parseResult(any())).thenReturn(new AgentResult(true, result));
        when(sandbox.execute(any()))
                .thenReturn(new SandboxResult(0, Map.of(), transcript, false, Duration.ofSeconds(1)));
    }

    /** Exactly one ledger row for the attempt, at the model and price frozen at admission, with these counts. */
    private void assertBooked(AgentJob stored, int calls, long input, long output, String provenance) {
        var snapshot = ConfigSnapshot.fromJson(stored.getConfigSnapshot(), mapper);
        var price = Objects.requireNonNull(snapshot.priceSnapshot());
        assertThat(stored.getLlmModel()).isEqualTo(snapshot.upstreamModelId());
        var row = jdbc.queryForMap(
                "SELECT model, total_calls, input_tokens, output_tokens, pricing_state, applied_price_id, "
                        + "usage_provenance, source_attempt FROM llm_usage_event WHERE source_id = ?",
                stored.getId());
        assertThat(number(row, "total_calls").intValue()).isEqualTo(calls);
        assertThat(number(row, "input_tokens").longValue()).isEqualTo(input);
        assertThat(number(row, "output_tokens").longValue()).isEqualTo(output);
        assertThat(number(row, "source_attempt").intValue()).isZero();
        assertThat(row.get("model")).isEqualTo(snapshot.upstreamModelId());
        assertThat(row.get("pricing_state")).isEqualTo(price.pricingState().name());
        Object appliedPriceId = row.get("applied_price_id");
        assertThat(appliedPriceId instanceof Number id ? Long.valueOf(id.longValue()) : null)
                .isEqualTo(price.appliedPriceId());
        assertThat(row.get("usage_provenance")).isEqualTo(provenance);
    }

    private static Number number(Map<String, @Nullable Object> row, String column) {
        return (Number) Objects.requireNonNull(row.get(column), column);
    }

    private long usageRows(UUID jobId) {
        Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM llm_usage_event WHERE source_id = ?", Long.class, jobId);
        return rows == null ? 0 : rows;
    }

    private WorkspaceAgentBinding binding() {
        String slug = "terminal-" + UUID.randomUUID();
        var connection = connections.save(LlmCatalogTestFixtures.connection(slug));
        LlmModel model = LlmCatalogTestFixtures.model(connection, slug, slug);
        model.setDataHandling(DataHandlingFacts.of(LlmDataOperator.OWN_ORGANISATION, null));
        model = models.save(model);
        var price = new LlmModelPrice();
        price.setModel(model);
        price.setPricingMode(PricingMode.NO_CHARGE);
        price.setEffectiveFrom(Instant.now());
        prices.saveAndFlush(price);
        var binding = new WorkspaceAgentBinding();
        binding.setWorkspace(workspace);
        binding.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        binding.setDataHandlingTier(DataHandlingTier.IN_HOUSE);
        binding.setInstanceModel(model);
        binding.setEnabled(true);
        binding.setMaxConcurrentJobs(5);
        return bindings.saveAndFlush(binding);
    }

    /** A queued review whose proxy already counted two calls, so its terminal write books real usage. */
    private AgentJob queued() {
        return Objects.requireNonNull(transactions.execute(status -> {
            var binding = bindings.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                            workspace.getId(), AgentPurpose.PRACTICE_REVIEW, DataHandlingTier.IN_HOUSE)
                    .orElseThrow();
            var job = new AgentJob();
            job.setWorkspace(workspace);
            job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
            job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
            job.setMetadata(mapper.createObjectNode());
            job.setConfigSnapshot(ConfigSnapshot.from(binding, resolver)
                    .withPriceSnapshot(admission.admit(binding).price())
                    .toJson(mapper));
            job.setLlmTotalCalls(2);
            job.setLlmTotalInputTokens(100);
            job.setLlmTotalOutputTokens(50);
            return jobs.saveAndFlush(job);
        }));
    }
}
