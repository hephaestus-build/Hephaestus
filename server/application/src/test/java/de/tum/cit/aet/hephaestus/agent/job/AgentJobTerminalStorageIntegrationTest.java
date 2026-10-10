package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmDataOperator;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPrice;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPriceRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.ModelKind;
import de.tum.cit.aet.hephaestus.agent.catalog.PricingMode;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures;
import de.tum.cit.aet.hephaestus.agent.handler.JobTypeHandlerRegistry;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobTypeHandler;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetDecision;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.integration.core.signal.PracticeReviewRefusalMetrics;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRevisionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
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
    private PiResultParser parser;

    @Autowired
    private AgentJobPrecomputeRunRepository precomputeRunRepository;

    @Autowired
    private PracticeRepository practices;

    @Autowired
    private PracticeRevisionRepository revisions;

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
        var binding = binding(AgentPurpose.PRACTICE_REVIEW, LlmApiProtocol.OPENAI_COMPLETIONS);
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
        when(issuer.issueForJobUntil(any(), anyLong(), anyInt(), any(), any())).thenReturn("test-job-token");
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
                new PrecomputeRunRecorder(parser, precomputeRunRepository, metrics, mapper),
                budgets,
                admission,
                mock(ArtifactSourceCatalogRegistry.class),
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
    @DisplayName("a runner report that covers the proxy is booked with the precompute chat calls it could not see")
    void shouldBookThePrecomputeChatCallsWithTheRunnersReport() {
        AgentJob job = queued();
        var reported = new AgentResult.LlmUsage("provider-name", 300, 120, null, null, null, null, 3);
        when(runner.parseResult(any())).thenReturn(new AgentResult(true, Map.of("review", "LGTM"), reported));
        when(sandbox.execute(any())).thenAnswer(invocation -> {
            // One precompute call on the chat model, as the proxy counts it: on its practice row and in the
            // job's own counters.
            transactions.executeWithoutResult(status -> {
                jobs.accumulatePrecomputeUsage(
                        job.getId(), 0, new PrecomputeCallUsage(ModelKind.CHAT, "comment-quality", null, 40, 10));
                jobs.accumulateLlmUsage(job.getId(), 0, new AgentJobLlmUsageDelta(40, 10, 0, 0, 0));
            });
            return new SandboxResult(0, Map.of(), "transcript", false, Duration.ofSeconds(1));
        });

        executor.processJob(job.getId());

        AgentJob stored = jobs.findById(job.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AgentJobStatus.COMPLETED);
        assertBooked(stored, 4, 340, 130, "RUNNER");
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

    @Test
    @DisplayName("a completed attempt books each precompute model the proxy counted, at the price its slot froze")
    void shouldBookEachPrecomputeModelWhenTheAttemptCompletes() {
        var embedding = binding(AgentPurpose.PRACTICE_EMBEDDING, LlmApiProtocol.OPENAI_EMBEDDINGS);
        when(policy.precomputeBinding(anyLong(), eq(AgentPurpose.PRACTICE_EMBEDDING), any(), any()))
                .thenReturn(Optional.of(embedding));
        AgentJob job = queued(Map.of(ModelKind.EMBEDDING, FrozenModel.from(embedding, resolver)));
        when(runner.parseResult(any())).thenReturn(new AgentResult(true, Map.of("review", "LGTM")));
        when(sandbox.execute(any())).thenAnswer(invocation -> {
            transactions.executeWithoutResult(status -> jobs.accumulatePrecomputeUsage(
                    job.getId(), 0, new PrecomputeCallUsage(ModelKind.EMBEDDING, "comment-quality", null, 40, 0)));
            return new SandboxResult(0, Map.of(), "transcript", false, Duration.ofSeconds(1));
        });

        executor.processJob(job.getId());

        var row = jdbc.queryForMap(
                "SELECT model, total_calls, input_tokens, pricing_state FROM llm_usage_event "
                        + "WHERE source_id = ? AND source_type = 'PRECOMPUTE_EMBEDDING'",
                job.getId());
        assertThat(row.get("model"))
                .isEqualTo(Objects.requireNonNull(embedding.getInstanceModel()).getUpstreamModelId());
        assertThat(number(row, "total_calls").intValue()).isEqualTo(1);
        assertThat(number(row, "input_tokens").longValue()).isEqualTo(40);
        assertThat(row.get("pricing_state")).isEqualTo("NO_CHARGE");
    }

    @Test
    @DisplayName("a completed attempt records what each staged precompute script did, as the runner reported it")
    void shouldRecordEachStagedScriptsRunWhenTheAttemptCompletes() {
        Practice decides = practice("comment-quality", "decide()");
        Practice scans = practice("error-handling", "scan()");
        AgentJob job = queued();
        var snapshot = mapper.createObjectNode();
        var admitted = snapshot.putArray("practices");
        for (Practice practice : List.of(decides, scans)) {
            admitted.addObject()
                    .put("slug", practice.getSlug())
                    .put(
                            "revisionId",
                            Objects.requireNonNull(practice.getCurrentRevision())
                                    .getId());
        }
        byte[] report = """
                {"practices":[{"slug":"comment-quality","status":"ok","leads":3,
                  "models":[{"slot":"decision","need":"required","bound":true,"notRated":{"deadline":2}}]}],
                 "truncated":false}""".getBytes(StandardCharsets.UTF_8);
        when(runner.parseResult(any())).thenReturn(new AgentResult(true, Map.of("review", "LGTM")));
        when(sandbox.execute(any())).thenAnswer(invocation -> {
            // The practices the attempt admitted, as its preparation records them before the sandbox runs.
            jdbc.update(
                    "UPDATE agent_job SET evidence_snapshot = CAST(? AS jsonb) WHERE id = ?",
                    mapper.writeValueAsString(snapshot),
                    job.getId());
            return new SandboxResult(
                    0,
                    Map.of(SandboxLayout.PRECOMPUTE_REPORT_FILE, report),
                    "transcript",
                    false,
                    Duration.ofSeconds(1));
        });

        executor.processJob(job.getId());

        var runs = jdbc.queryForList(
                "SELECT practice_slug, attempt, status, leads, practice_revision_id, CAST(models AS text) AS models "
                        + "FROM agent_job_precompute_run WHERE job_id = ? ORDER BY practice_slug",
                job.getId());
        assertThat(runs).hasSize(2);
        assertThat(runs.get(0))
                .containsEntry("practice_slug", "comment-quality")
                .containsEntry("attempt", 0)
                .containsEntry("status", "OK")
                .containsEntry("leads", 3)
                .containsEntry(
                        "practice_revision_id",
                        Objects.requireNonNull(decides.getCurrentRevision()).getId());
        assertThat(mapper.readTree(String.valueOf(runs.get(0).get("models")))).isEqualTo(mapper.readTree("""
                        [{"purpose":"PRACTICE_DECISION","need":"REQUIRED","bound":true,
                          "notRated":[{"reason":"DEADLINE","count":2}]}]"""));
        assertThat(runs.get(1))
                .containsEntry("practice_slug", "error-handling")
                .containsEntry("status", "NOT_FINISHED")
                .containsEntry("models", null);
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

    /** A practice of the workspace whose current revision holds this precompute script. */
    private Practice practice(String slug, String script) {
        Practice practice = new Practice();
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        practice.setWorkspace(workspace);
        practice.setSlug(slug);
        practice.setName(slug);
        practice.setCriteria("Review the change");
        PracticeTestEvidence.configure(practice, ScmSignals.PULL_REQUEST_OPENED);
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        practice.setPrecomputeScript(script);
        practice = practices.save(practice);
        practice.setCurrentRevision(revisions.save(new PracticeRevision(practice, 1)));
        return practices.save(practice);
    }

    private WorkspaceAgentBinding binding(AgentPurpose purpose, LlmApiProtocol apiProtocol) {
        String slug = "terminal-" + UUID.randomUUID();
        var connection = LlmCatalogTestFixtures.connection(slug);
        connection.setApiProtocol(apiProtocol);
        connection = connections.save(connection);
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
        binding.setPurpose(purpose);
        binding.setDataHandlingTier(DataHandlingTier.IN_HOUSE);
        binding.setInstanceModel(model);
        binding.setEnabled(true);
        binding.setMaxConcurrentJobs(5);
        return bindings.saveAndFlush(binding);
    }

    /** A queued review whose proxy already counted two calls, so its terminal write books real usage. */
    private AgentJob queued() {
        return queued(null);
    }

    private AgentJob queued(@Nullable Map<ModelKind, FrozenModel> precompute) {
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
                    .withPrecompute(precompute)
                    .toJson(mapper));
            job.setLlmTotalCalls(2);
            job.setLlmTotalInputTokens(100);
            job.setLlmTotalOutputTokens(50);
            return jobs.saveAndFlush(job);
        }));
    }
}
