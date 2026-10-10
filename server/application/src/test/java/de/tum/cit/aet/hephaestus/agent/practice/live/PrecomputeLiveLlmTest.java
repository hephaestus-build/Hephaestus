package de.tum.cit.aet.hephaestus.agent.practice.live;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.catalog.CreateWorkspaceLlmConnectionRequestDTO;
import de.tum.cit.aet.hephaestus.agent.catalog.CreateWorkspaceLlmModelRequestDTO;
import de.tum.cit.aet.hephaestus.agent.catalog.InstanceLlmSettings;
import de.tum.cit.aet.hephaestus.agent.catalog.InstanceLlmSettingsRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmAuthMode;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.ModelKind;
import de.tum.cit.aet.hephaestus.agent.catalog.PricingMode;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmConnectionService;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmModelService;
import de.tum.cit.aet.hephaestus.agent.config.AgentBindingRequestDTO;
import de.tum.cit.aet.hephaestus.agent.config.AgentBindingService;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayProperties;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobLifecycleService;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobPrecomputeUsage;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.agent.job.PrecomputeKindTotal;
import de.tum.cit.aet.hephaestus.agent.job.ReviewMemberAiPolicy;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeAgentRequest;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEvent;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEventRepository;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.agent.usage.PricingState;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.testconfig.LiveLlmCredentials;
import de.tum.cit.aet.hephaestus.testconfig.LiveLlmTest;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The precompute models end to end against real models: workspace bindings, the frozen snapshot, the
 * precompute token, the models file that {@link PracticePiAdapter} writes, the real LLM proxy on the
 * sandbox gateway connector, the per-attempt usage rows and the ledger rows at the end of the attempt.
 * The precompute runner from {@code docker/agents/precompute} runs {@code fixtures/every-slot.ts}
 * against this server.
 *
 * <p>{@code docs/contributor/testing.mdx} § Live precompute models names the variables. The worker
 * role is on so that the proxy and its gateway connector exist. The agent stays off, so no poller
 * claims the job.
 */
@LiveLlmTest
class PrecomputeLiveLlmTest extends AbstractWorkspaceIntegrationTest {

    private static final Path PRECOMPUTE = Path.of("..", "..", "docker", "agents", "precompute")
            .toAbsolutePath()
            .normalize();

    private static final int GATEWAY_PORT = freePort();

    private static final Path FABRIC_ROOT = temporaryDirectory("precompute-live-fabric-");

    /** The runner's whole stage. Each script still ends at its own deadline. */
    private static final Duration STAGE = Duration.ofMinutes(5);

    private static final String CHANGE = String.join(
            "\n",
            "diff --git a/src/cart.ts b/src/cart.ts",
            "--- a/src/cart.ts",
            "+++ b/src/cart.ts",
            "@@ -1,1 +1,12 @@",
            " export class Cart {",
            "+  // increment the item count by one",
            "+  count += 1;",
            "+  // retry once: the payment API drops the first call after an idle period",
            "+  await pay(order).catch(() => pay(order));",
            "+  // TODO clean this up before release",
            "+  const legacy = true;",
            "+  // timeout in seconds",
            "+  const timeoutMs = 30_000;",
            "+  // TEMP hardcoded for the demo, remove before merge",
            "+  const discount = 0.5;",
            "+}");

    @DynamicPropertySource
    static void workerRole(DynamicPropertyRegistry registry) {
        registry.add("hephaestus.runtime.worker.enabled", () -> "true");
        registry.add("hephaestus.sandbox.gateway.port", () -> Integer.toString(GATEWAY_PORT));
        // The worker's maintenance removes the containers of its owner and the ended job folders under its
        // root. Other checkouts and test runs on this host keep theirs.
        registry.add("hephaestus.sandbox.docker.owner", () -> "precompute-live-test");
        registry.add("hephaestus.fabric.root", FABRIC_ROOT::toString);
        // No sandbox runs here, so the worker does not pull the agent image.
        registry.add("hephaestus.agent.image.pull-policy", () -> "NEVER");
    }

    @Autowired
    private InstanceLlmSettingsRepository instanceLlmSettings;

    @Autowired
    private WorkspaceLlmConnectionService connections;

    @Autowired
    private WorkspaceLlmModelService models;

    @Autowired
    private AgentBindingService bindings;

    @Autowired
    private ReviewMemberAiPolicy reviewPolicy;

    @Autowired
    private LlmModelResolver resolver;

    @Autowired
    private LlmAdmissionService admission;

    @Autowired
    private AgentJobRepository jobRepository;

    @Autowired
    private LlmUsageEventRepository usageEvents;

    @Autowired
    private WorkerJwtIssuer jwtIssuer;

    @Autowired
    private WorkerProperties workerProperties;

    @Autowired
    private SandboxGatewayProperties gatewayProperties;

    @Autowired
    private PracticePiAdapter practiceAdapter;

    @Autowired
    private PracticeReviewProperties reviewProperties;

    @Autowired
    private AgentJobLifecycleService lifecycle;

    @Autowired
    private ObjectMapper objectMapper;

    private @Nullable Path root;

    @AfterEach
    void cleanUp() throws IOException {
        WorkspaceContextHolder.clearContext();
        if (root != null) {
            FileSystemUtils.deleteRecursively(root);
        }
    }

    @AfterAll
    static void removeFabricRoot() throws IOException {
        FileSystemUtils.deleteRecursively(FABRIC_ROOT);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void shouldServeEveryBoundSlotThroughTheProxyAndBillItWhenTheAttemptEnds() throws Exception {
        LiveLlmCredentials chat = LiveLlmCredentials.fromEnv();
        @Nullable String embeddingModel = variable("HEPHAESTUS_LIVE_EMBEDDING_MODEL");
        @Nullable String rerankingModel = variable("HEPHAESTUS_LIVE_RERANKING_MODEL");
        @Nullable String decisionBase = variable("HEPHAESTUS_LIVE_DECISION_BASE_URL");
        @Nullable String decisionKey = variable("HEPHAESTUS_LIVE_DECISION_API_KEY");
        @Nullable String decisionModel = variable("HEPHAESTUS_LIVE_DECISION_MODEL");

        Workspace workspace = workspace();
        WorkspaceContext context =
                WorkspaceContext.fromWorkspace(workspace, Set.of(WorkspaceMembership.WorkspaceRole.ADMIN), null);
        WorkspaceContextHolder.setContext(context);
        EnumSet<ModelKind> boundKinds = EnumSet.noneOf(ModelKind.class);
        bind(
                context,
                AgentPurpose.PRACTICE_REVIEW,
                LlmApiProtocol.OPENAI_COMPLETIONS,
                chat.baseUrl(),
                chat.apiKey(),
                chat.model());
        Map<String, Map<String, Object>> warmUps = new LinkedHashMap<>();
        warmUps.put(
                "chat/completions",
                Map.of(
                        "model",
                        chat.model(),
                        "messages",
                        List.of(Map.of("role", "user", "content", "Hi")),
                        "max_tokens",
                        1));
        if (embeddingModel != null) {
            bind(
                    context,
                    AgentPurpose.PRACTICE_EMBEDDING,
                    LlmApiProtocol.OPENAI_EMBEDDINGS,
                    chat.baseUrl(),
                    chat.apiKey(),
                    embeddingModel);
            boundKinds.add(ModelKind.EMBEDDING);
            warmUps.put("embeddings", Map.of("model", embeddingModel, "input", List.of("Hi")));
        }
        if (rerankingModel != null) {
            bind(
                    context,
                    AgentPurpose.PRACTICE_RERANKING,
                    LlmApiProtocol.COHERE_RERANK,
                    chat.baseUrl(),
                    chat.apiKey(),
                    rerankingModel);
            boundKinds.add(ModelKind.RERANKING);
            warmUps.put("rerank", Map.of("model", rerankingModel, "query", "Hi", "documents", List.of("Hi")));
        }
        if (decisionBase != null && decisionKey != null && decisionModel != null) {
            bind(
                    context,
                    AgentPurpose.PRACTICE_DECISION,
                    LlmApiProtocol.OPENAI_DECISIONS,
                    decisionBase,
                    decisionKey,
                    decisionModel);
            boundKinds.add(ModelKind.DECISION);
        }

        EnumSet<ModelKind> called = EnumSet.copyOf(boundKinds);
        called.add(ModelKind.CHAT);

        AgentJob job = runningJob(workspace);
        ConfigSnapshot snapshot = ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper);
        Map<ModelKind, FrozenModel> frozen = snapshot.precomputeModels();
        assertThat(frozen.keySet()).isEqualTo(called);

        String precomputeToken = jwtIssuer.issueForJobUntil(
                job.getId(),
                context.id(),
                job.getRetryCount(),
                Instant.now().plus(STAGE).plus(Duration.ofMinutes(5)),
                WorkerJwtIssuer.LLM_PRECOMPUTE_SCOPE);
        byte[] modelsFile = practiceAdapter
                .buildSandboxSpec(new PracticeAgentRequest(
                        snapshot.apiProtocol(),
                        snapshot.upstreamModelId(),
                        snapshot.contextWindow(),
                        snapshot.maxOutputTokens(),
                        snapshot.reasoningEffort(),
                        "review-token-unused-here",
                        snapshot.timeoutSeconds(),
                        precomputeToken,
                        frozen))
                .inputFiles()
                .get(SandboxLayout.PRECOMPUTE_MODELS_FILE);

        warmUp(chat, warmUps);
        RunnerRun run = runEverySlot(Objects.requireNonNull(modelsFile), precomputeToken);

        JsonNode result = run.result();
        String report = result.toPrettyString() + "\n" + run.log();
        assertThat(result.path("status").asString()).as(report).isEqualTo("ok");
        assertThat(result.path("dropped").asInt()).as(report).isZero();
        JsonNode slots = result.path("models");
        for (ModelKind kind : called) {
            assertThat(slots.path(kind.slot()).path("bound").asBoolean())
                    .as("%s is available: %s", kind, report)
                    .isTrue();
            assertThat(slots.path(kind.slot()).path("notRated").propertyNames())
                    .as("%s rated every call: %s", kind, report)
                    .isEmpty();
        }
        for (ModelKind kind : EnumSet.complementOf(called)) {
            assertThat(slots.path(kind.slot()).path("bound").asBoolean())
                    .as("an unbound %s is not available: %s", kind, report)
                    .isFalse();
        }

        Map<ModelKind, PrecomputeKindTotal> usage =
                jobRepository.sumPrecomputeUsageByKind(context.id(), job.getId(), job.getRetryCount()).stream()
                        .collect(Collectors.toMap(PrecomputeKindTotal::modelKind, Function.identity()));
        assertThat(usage.keySet()).isEqualTo(called);

        lifecycle.cancel(context.id(), job.getId());

        Map<LlmUsageSourceType, LlmUsageEvent> ledger = usageEvents.findAll().stream()
                .filter(event -> event.getSourceId().equals(job.getId()))
                .filter(event -> event.getSourceType() != LlmUsageSourceType.AGENT_JOB)
                .collect(Collectors.toMap(LlmUsageEvent::getSourceType, Function.identity()));
        assertThat(ledger.keySet())
                .containsExactlyInAnyOrderElementsOf(boundKinds.stream()
                        .map(AgentJobPrecomputeUsage::ledgerSourceType)
                        .toList());
        for (ModelKind kind : boundKinds) {
            LlmUsageEvent row = Objects.requireNonNull(ledger.get(AgentJobPrecomputeUsage.ledgerSourceType(kind)));
            PrecomputeKindTotal counted = Objects.requireNonNull(usage.get(kind));
            assertThat(counted.inputTokens()).as("%s input tokens", kind).isPositive();
            assertThat(row.getModel())
                    .isEqualTo(Objects.requireNonNull(frozen.get(kind)).upstreamModelId());
            assertThat((long) row.getTotalCalls()).isEqualTo(counted.calls());
            assertThat(row.getInputTokens()).isEqualTo(counted.inputTokens());
            assertThat(row.getSourceAttempt()).isEqualTo(job.getRetryCount());
            assertThat(row.getPricingState()).isEqualTo(PricingState.PRICED);
        }
    }

    private static @Nullable String variable(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }

    private Workspace workspace() {
        InstanceLlmSettings settings = instanceLlmSettings.findById((short) 1).orElseGet(() -> {
            InstanceLlmSettings created = new InstanceLlmSettings();
            created.setId((short) 1);
            return created;
        });
        settings.setAllowWorkspaceConnections(true);
        instanceLlmSettings.save(settings);

        String slug = "precompute-live-" + System.nanoTime();
        Workspace workspace =
                createWorkspace(slug, "Precompute live", slug + "-org", AccountType.ORG, persistUser(slug + "-owner"));
        ensureAdminMembership(workspace);
        return workspace;
    }

    /** One workspace connection and priced model per purpose, bound to the workspace's undeclared slot. */
    private void bind(
            WorkspaceContext context,
            AgentPurpose purpose,
            LlmApiProtocol protocol,
            String baseUrl,
            String apiKey,
            String upstreamModelId) {
        String name = purpose.name().toLowerCase(Locale.ROOT).replace('_', '-');
        WorkspaceLlmConnection connection = connections.create(
                context,
                new CreateWorkspaceLlmConnectionRequestDTO(
                        null, name, baseUrl, protocol, LlmAuthMode.BEARER, apiKey, true, null));
        Long modelId = models.create(
                        context,
                        connection.getId(),
                        new CreateWorkspaceLlmModelRequestDTO(
                                null,
                                name,
                                upstreamModelId,
                                null,
                                null,
                                null,
                                null,
                                null,
                                true,
                                PricingMode.PRICED,
                                new BigDecimal("0.10"),
                                new BigDecimal("0.40"),
                                null,
                                null,
                                null,
                                null))
                .getId();
        bindings.upsertBinding(
                context,
                purpose,
                DataHandlingTier.UNDECLARED,
                new AgentBindingRequestDTO(null, modelId, null, null, null, true));
    }

    /**
     * A running review owned by this worker, so that the proxy accepts its token. Submission freezes the
     * bound models and the claim prices them; both are private to the job pipeline and need a reviewable
     * pull request, so this does the same from the same bindings.
     */
    private AgentJob runningJob(Workspace workspace) {
        Long workspaceId = workspace.getId();
        JsonNode metadata = objectMapper.createObjectNode();
        AgentJobType type = AgentJobType.PULL_REQUEST_REVIEW;
        var review = reviewPolicy.binding(workspaceId, type, metadata).orElseThrow();
        Map<ModelKind, FrozenModel> slots = new EnumMap<>(ModelKind.class);
        for (AgentPurpose purpose : AgentPurpose.precompute()) {
            reviewPolicy
                    .precomputeBinding(workspaceId, purpose, type, metadata)
                    .ifPresent(binding -> slots.put(
                            purpose.kind(),
                            FrozenModel.from(binding, resolver)
                                    .withPriceSnapshot(admission.admit(binding).price())));
        }
        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setJobType(type);
        job.setMetadata(metadata);
        job.setStatus(AgentJobStatus.RUNNING);
        job.setWorkerId(workerProperties.resolvedWorkerId());
        job.setStartedAt(Instant.now());
        job.setExecutionStartedAt(Instant.now());
        job.setConfigSnapshot(ConfigSnapshot.from(review, resolver)
                .withPriceSnapshot(admission.admit(review).price())
                .withPrecompute(slots.isEmpty() ? null : slots)
                .toJson(objectMapper));
        return jobRepository.saveAndFlush(job);
    }

    /**
     * The model host loads a model on its first request, which can take longer than a script's deadline:
     * the runner would then report the slot as unrated for its deadline. Each self-hosted model answers
     * one request here first.
     */
    private void warmUp(LiveLlmCredentials host, Map<String, Map<String, Object>> bodies) {
        String base = host.baseUrl().replaceAll("/+$", "");
        try (HttpClient client = HttpClient.newHttpClient()) {
            CompletableFuture.allOf(bodies.entrySet().stream()
                            .map(call -> client.sendAsync(
                                    HttpRequest.newBuilder(URI.create(base + "/" + call.getKey()))
                                            .timeout(Duration.ofMinutes(4))
                                            .header("Authorization", "Bearer " + host.apiKey())
                                            .header("Content-Type", "application/json")
                                            .POST(HttpRequest.BodyPublishers.ofByteArray(
                                                    objectMapper.writeValueAsBytes(call.getValue())))
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding()))
                            .toArray(CompletableFuture[]::new))
                    .join();
        }
    }

    /** Stages the fixture as {@code pi-precompute.ts} does and runs the runner against the gateway. */
    private RunnerRun runEverySlot(byte[] modelsFile, String precomputeToken) throws Exception {
        Path workDir = temporaryDirectory("precompute-live-").toRealPath();
        root = workDir;
        Path practices = Files.createDirectories(workDir.resolve("stage/practices"));
        Files.createSymbolicLink(workDir.resolve("stage/lib"), PRECOMPUTE.resolve("lib"));
        Files.copy(PRECOMPUTE.resolve("fixtures/every-slot.ts"), practices.resolve("every-slot.ts"));
        Files.writeString(workDir.resolve("package.json"), "{\"type\":\"module\"}\n");
        Files.writeString(workDir.resolve("change.diff"), CHANGE);
        // The repository the agent searches: the runner's library, which declares parseDiff.
        FileSystemUtils.copyRecursively(PRECOMPUTE.resolve("lib"), workDir.resolve("repo/lib"));
        Path models = Files.write(workDir.resolve(SandboxLayout.PRECOMPUTE_MODELS_FILE), modelsFile);
        Path output = workDir.resolve("out");
        Path log = workDir.resolve("runner.log");

        ProcessBuilder runner = new ProcessBuilder(
                        "node",
                        PRECOMPUTE.resolve("runner.ts").toString(),
                        "--repo",
                        workDir.resolve("repo").toString(),
                        "--diff",
                        workDir.resolve("change.diff").toString(),
                        "--practices",
                        practices.toString(),
                        "--output",
                        output.toString(),
                        "--models",
                        models.toString(),
                        "--tokens",
                        Long.toString(reviewProperties.precomputeMaxTokensPerAttempt()),
                        "--stage-ms",
                        Long.toString(STAGE.minusSeconds(10).toMillis()))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        // Only what pi-precompute.sh passes: the runner never sees this JVM's credentials.
        Map<String, String> environment = runner.environment();
        String path = Objects.requireNonNullElse(environment.get("PATH"), "/usr/local/bin:/usr/bin:/bin");
        environment.clear();
        environment.put("PATH", path);
        environment.put("LLM_PROXY_URL", "http://127.0.0.1:" + gatewayProperties.port() + "/internal/llm");
        environment.put("PRECOMPUTE_PROXY_TOKEN", precomputeToken);

        Process process = runner.start();
        if (!process.waitFor(STAGE.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("runner did not finish:\n" + Files.readString(log));
        }
        String runnerLog = Files.readString(log);
        assertThat(process.exitValue()).as(runnerLog).isZero();
        Path resultFile = output.resolve("every-slot.json");
        assertThat(resultFile).as(runnerLog).exists();
        return new RunnerRun(objectMapper.readTree(resultFile.toFile()), runnerLog);
    }

    /** The practice's result, and what the runner logged while it ran: its steps and their errors. */
    private record RunnerRun(JsonNode result, String log) {}

    private static Path temporaryDirectory(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
