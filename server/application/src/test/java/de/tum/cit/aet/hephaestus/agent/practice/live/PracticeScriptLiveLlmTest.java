package de.tum.cit.aet.hephaestus.agent.practice.live;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.CreateWorkspaceLlmConnectionRequestDTO;
import de.tum.cit.aet.hephaestus.agent.catalog.CreateWorkspaceLlmModelRequestDTO;
import de.tum.cit.aet.hephaestus.agent.catalog.InstanceLlmSettings;
import de.tum.cit.aet.hephaestus.agent.catalog.InstanceLlmSettingsRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmAuthMode;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.PricingMode;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmConnectionService;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmModelService;
import de.tum.cit.aet.hephaestus.agent.config.AgentBindingRequestDTO;
import de.tum.cit.aet.hephaestus.agent.config.AgentBindingService;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.config.MemberAiRoutingAdapter;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConnector;
import de.tum.cit.aet.hephaestus.agent.handler.EvidenceSnapshotFixtures;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeCatalogInjector;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeScriptInputs;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.PracticeScriptKindTotal;
import de.tum.cit.aet.hephaestus.agent.job.PracticeScriptRunRecorderFixtures;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobInput;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobLifecycleService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.agent.job.ScriptRun;
import de.tum.cit.aet.hephaestus.agent.job.ScriptRunRepository;
import de.tum.cit.aet.hephaestus.agent.job.ScriptUsage;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeAgentRequest;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEvent;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEventRepository;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.AttemptJwt;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeReadSource;
import de.tum.cit.aet.hephaestus.practices.PracticeReads;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewQueue;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequest;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.practices.spi.ScriptKind;
import de.tum.cit.aet.hephaestus.practices.spi.ScriptRunStatus;
import de.tum.cit.aet.hephaestus.testconfig.LiveLlmCredentials;
import de.tum.cit.aet.hephaestus.testconfig.LiveLlmTest;
import de.tum.cit.aet.hephaestus.testconfig.PiSdkInstallation;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// Enable the worker role for the gateway and proxy, but keep the agent off so no poller claims the fixture job.
@LiveLlmTest
class PracticeScriptLiveLlmTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private ItemIndexService itemWriter;

    @Autowired
    private List<ItemSource> itemSources;

    @Autowired
    private ReviewService reviews;

    @Autowired
    private ReviewQueue reviewQueue;

    @Autowired
    private ReviewJobAssignments reviewAssignments;

    @Autowired
    private MemberAiRoutingAdapter runtimeModelRouting;

    @Autowired
    private PracticeCatalogInjector catalogInputs;

    @Autowired
    private PracticeScriptInputs scriptInputs;

    @Autowired
    private PracticeVersionService runtimePracticeVersions;

    private static final Path EVERY_SLOT = Path.of(
                    "..", "..", "docker", "agents", "practice-scripts", "fixtures", "every-slot.ts")
            .toAbsolutePath()
            .normalize();

    private static final String SLUG = "every-slot";

    private static final String TITLE = "Add the login service";

    private static final String BODY = "Adds the login service the app signs in with.";

    private static final String SOURCE_BRANCH = "feat/login";

    private static final String CRITERIA = """
        # Comments explain why

        ## Standard
        An added comment states a constraint the code cannot express, and no added comment restates the code or
        marks unfinished work that is merged anyway.
        """;

    private static final Path FABRIC_ROOT = temporaryDirectory("practice-script-live-fabric-");

    @DynamicPropertySource
    static void workerRole(DynamicPropertyRegistry registry) {
        registry.add("hephaestus.runtime.worker.enabled", () -> "true");
        registry.add("hephaestus.sandbox.gateway.port", () -> "0");
        // Cleanup is scoped to this worker owner and root; other checkouts share the host.
        registry.add("hephaestus.sandbox.docker.owner", () -> "practice-script-live-test");
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
    private LlmModelResolver resolver;

    @Autowired
    private LlmAdmissionService admission;

    @Autowired
    private ReviewJobRepository jobRepository;

    @Autowired
    private LlmUsageEventRepository usageEvents;

    @Autowired
    private WorkerJwtIssuer jwtIssuer;

    @Autowired
    private WorkerProperties workerProperties;

    @Autowired
    private SandboxGatewayConnector gateway;

    @Autowired
    private PracticePiAdapter practiceAdapter;

    @Autowired
    private ReviewJobLifecycleService lifecycle;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private PullRequestRepository pullRequests;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private JobEvidenceFiles evidenceFiles;

    @Autowired
    private PiResultParser parser;

    @Autowired
    private ScriptRunRepository runs;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ObjectMapper objectMapper;

    private @Nullable Path root;

    private final List<PreparedJobInputs> prepared = new ArrayList<>();

    @BeforeAll
    static void installPiSdk() throws Exception {
        PiSdkInstallation.ensureInstalled();
        LiveRunnerWorkspace.installToolkit();
    }

    @AfterEach
    void cleanUp() throws IOException {
        WorkspaceContextHolder.clearContext();
        prepared.forEach(PreparedJobInputs::close);
        prepared.clear();
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
    void shouldServeEveryBoundSlotAndTheReviewThroughTheProxyAndAdmitWithOneJobToken() throws Exception {
        LiveLlmCredentials chat = LiveLlmCredentials.fromEnv();
        @Nullable String embeddingModel = variable("HEPHAESTUS_LIVE_EMBEDDING_MODEL");
        @Nullable String rerankingModel = variable("HEPHAESTUS_LIVE_RERANKING_MODEL");
        @Nullable String decisionBase = variable("HEPHAESTUS_LIVE_DECISION_BASE_URL");
        @Nullable String decisionKey = variable("HEPHAESTUS_LIVE_DECISION_API_KEY");
        @Nullable String decisionModel = variable("HEPHAESTUS_LIVE_DECISION_MODEL");

        User developer = persistUser("practice-script-live-developer");
        Workspace workspace = workspace(developer);
        WorkspaceContext context =
                WorkspaceContext.fromWorkspace(workspace, Set.of(Membership.WorkspaceRole.ADMIN), null);
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
        // As live.test.ts binds it: a decision endpoint when one is named, else the chat model through its logprobs.
        if (decisionBase != null && decisionKey != null && decisionModel != null) {
            bind(
                    context,
                    AgentPurpose.PRACTICE_DECISION,
                    LlmApiProtocol.OPENAI_DECISIONS,
                    decisionBase,
                    decisionKey,
                    decisionModel);
        } else {
            bind(
                    context,
                    AgentPurpose.PRACTICE_DECISION,
                    LlmApiProtocol.OPENAI_COMPLETIONS,
                    chat.baseUrl(),
                    chat.apiKey(),
                    chat.model());
        }
        boundKinds.add(ModelKind.DECISION);
        EnumSet<ModelKind> called = EnumSet.copyOf(boundKinds);
        called.add(ModelKind.CHAT);

        Path workDir = temporaryDirectory("practice-script-live-").toRealPath();
        root = workDir;
        Practice practice = practice(workspace, Files.readString(EVERY_SLOT));
        LiveRunnerWorkspace.stageCartChange(workDir);
        ReviewJob job = capture(runningJob(workspace, practice, accountId(developer)), workDir, developer, workspace);
        ConfigSnapshot snapshot = ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper);
        Map<ModelKind, FrozenModel> frozen = snapshot.practiceScriptModels();
        assertThat(frozen.keySet()).isEqualTo(called);
        Instant workDeadline = Instant.now().plus(Duration.ofSeconds(LiveRunnerWorkspace.TIMEOUT_SECONDS + 300L));
        String jobToken = jwtIssuer.issueForAttempt(
                AttemptJwt.Kind.REVIEW,
                job.getId(),
                context.id(),
                job.getAttempt(),
                workDeadline,
                workDeadline.plus(Duration.ofMinutes(10)));
        PracticeSandboxSpec spec = practiceAdapter.buildSandboxSpec(new PracticeAgentRequest(
                snapshot.model().apiProtocol().wire(),
                snapshot.model().upstreamModelId(),
                snapshot.contextWindow(),
                snapshot.maxOutputTokens(),
                snapshot.model().reasoningEffort(),
                jobToken,
                LiveRunnerWorkspace.TIMEOUT_SECONDS,
                frozen));
        assertThat(spec.environment())
                .as("one job token, no second credential")
                .doesNotContainKey("PRACTICE_SCRIPT_PROXY_TOKEN");
        LiveRunnerWorkspace.stageSpec(workDir, spec.inputFiles());

        var assigned = reviewAssignments.load(job);
        var frozenReview = reviewAssignments.review(job);
        var staged = new LinkedHashMap<String, byte[]>();
        catalogInputs.inject(staged, assigned, ReviewKind.ITEM, Map.of());
        for (var assignment : assigned)
            staged.putAll(scriptInputs.stage(
                    frozenReview,
                    assignment,
                    objectMapper.readTree(workDir.resolve(SandboxLayout.CONTEXT_PREFIX + "metadata.json")
                            .toFile()),
                    Objects.requireNonNull(job.getStartedAt()),
                    null));
        for (var entry : staged.entrySet()) {
            Path target = workDir.resolve(entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
        }
        Files.copy(
                EVERY_SLOT.resolveSibling("comment-kinds.ts"),
                workDir.resolve(SandboxLayout.PRACTICE_SCRIPT_PRACTICES_PREFIX + "comment-kinds.ts"));
        LiveRunnerWorkspace.stageTask(
                workDir,
                "Review merge request #1 in test/fixture against " + SLUG + ".",
                job.getReviewId(),
                job.getId(),
                job.getAttempt(),
                job.getWorkspaceId());
        jobRepository.saveAndFlush(job);

        warmUp(chat, warmUps);
        Path log = workDir.resolve("runner.log");
        Process runner = LiveRunnerWorkspace.spawn(
                workDir, spec.environment(), "http://127.0.0.1:" + gateway.port() + "/internal/llm", jobToken, log);
        if (!runner.waitFor(LiveRunnerWorkspace.TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            runner.destroyForcibly();
            throw new AssertionError("runner did not finish:\n" + Files.readString(log));
        }
        String runnerLog = Files.readString(log);

        ReviewJob ended = jobRepository.findById(job.getId()).orElseThrow();

        String refusal = ended.getCapture() == null
                ? ""
                : ended.getCapture().path("withheld").get(0) == null
                        ? ""
                        : ended.getCapture()
                                .path("withheld")
                                .get(0)
                                .path("reason")
                                .asString();
        assertThat(ResultAdmissionService.isAdmitted(ended) || !refusal.isBlank())
                .as("the server's admission decided on the submission: %s%n%s", ended.getCapture(), runnerLog)
                .isTrue();
        assertThat(refusal).isNotEqualTo("malformed_admission");

        Path reportFile = workDir.resolve(SandboxLayout.OUTPUT_PREFIX + SandboxLayout.PRACTICE_SCRIPT_REPORT_FILE);
        assertThat(reportFile).as(runnerLog).exists();
        transactions.executeWithoutResult(status -> PracticeScriptRunRecorderFixtures.record(
                parser,
                runs,
                meterRegistry,
                objectMapper,
                reviewAssignments,
                ended,
                readBytes(reportFile),
                frozen,
                Instant.now()));
        ScriptRun run = runs.findByWorkspaceIdAndJobIdIn(context.id(), List.of(job.getId())).stream()
                .filter(row -> row.getId().getPracticeId() == practice.getId())
                .findFirst()
                .orElseThrow();
        String report = Files.readString(reportFile) + "\n" + runnerLog;
        assertThat(run.getStatus()).as(report).isEqualTo(ScriptRunStatus.OK);
        assertThat(run.getScriptKind()).isEqualTo(ScriptKind.CUSTOM);
        JsonNode calls = Objects.requireNonNull(run.getCalls());
        assertThat(calls.path("review").asInt())
                .as("one review session: %s", report)
                .isEqualTo(1);
        for (ModelKind kind : called) {
            assertThat(calls.path(kind.name().toLowerCase(Locale.ROOT)).asInt())
                    .as("%s was called: %s", kind, report)
                    .isPositive();
        }
        for (JsonNode slot : Objects.requireNonNull(run.getModels())) {
            assertThat(slot.path("failures").isEmpty())
                    .as("every bound slot answered every call: %s", report)
                    .isTrue();
        }

        Map<ModelKind, PracticeScriptKindTotal> usage =
                jobRepository.sumPracticeScriptUsageByKind(context.id(), job.getId(), job.getAttempt()).stream()
                        .collect(Collectors.toMap(PracticeScriptKindTotal::modelKind, Function.identity()));
        assertThat(usage.keySet()).isEqualTo(called);

        lifecycle.cancel(context.id(), job.getId());

        Map<UsageSourceType, LlmUsageEvent> ledger = usageEvents.findAll().stream()
                .filter(event -> event.getSourceId().equals(job.getId()))
                .filter(event -> event.getSourceType() != UsageSourceType.REVIEW_JOB)
                .collect(Collectors.toMap(LlmUsageEvent::getSourceType, Function.identity()));
        assertThat(ledger.keySet())
                .containsExactlyInAnyOrderElementsOf(
                        boundKinds.stream().map(ScriptUsage::ledgerSourceType).toList());
        for (ModelKind kind : boundKinds) {
            LlmUsageEvent row = Objects.requireNonNull(ledger.get(ScriptUsage.ledgerSourceType(kind)));
            PracticeScriptKindTotal counted = Objects.requireNonNull(usage.get(kind));
            assertThat(counted.inputTokens()).as("%s input tokens", kind).isPositive();
            assertThat(row.getModel())
                    .isEqualTo(Objects.requireNonNull(frozen.get(kind)).upstreamModelId());
            assertThat((long) row.getTotalCalls()).isEqualTo(counted.calls());
            assertThat(row.getInputTokens()).isEqualTo(counted.inputTokens());
            assertThat(row.getSourceAttempt()).isEqualTo(job.getAttempt());
            assertThat(row.getPricingState())
                    .as("%s pricing of %s", kind, counted)
                    .isEqualTo(UsagePricingState.PRICED);
        }
    }

    private static @Nullable String variable(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }

    private Workspace workspace(User developer) {
        InstanceLlmSettings settings = instanceLlmSettings.findById((short) 1).orElseGet(() -> {
            InstanceLlmSettings created = new InstanceLlmSettings();
            created.setId((short) 1);
            return created;
        });
        settings.setAllowWorkspaceConnections(true);
        instanceLlmSettings.save(settings);

        String slug = "practice-script-live-" + System.nanoTime();
        Workspace workspace = createWorkspace(
                slug, "Practice script live", slug + "-org", AccountType.ORG, persistUser(slug + "-owner"));
        workspace.getFeatures().setPracticesEnabled(true);
        workspace = workspaces.saveAndFlush(workspace);
        ensureAdminMembership(workspace);
        ensureMembership(workspace, developer, Membership.WorkspaceRole.MEMBER);
        connectActivitySource(workspace, ensureGitHubProvider());
        return workspace;
    }

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
                                // Some endpoints report cache reads and writes; an unknown rate leaves the row
                                // UNPRICED.
                                new BigDecimal("0.05"),
                                new BigDecimal("0.10"),
                                null,
                                null))
                .getId();
        bindings.upsertBinding(
                context,
                purpose,
                DataHandlingTier.UNDECLARED,
                new AgentBindingRequestDTO(null, modelId, null, null, true));
    }

    private Practice practice(Workspace workspace, String program) {
        var base = DefinitionTestFixtures.definition(new ItemKind("scm.pull_request"), "Every slot", CRITERIA, program);
        var definition = new PracticeDefinition(
                base.name(),
                base.reviewOn(),
                base.appliesWhen(),
                base.about(),
                new PracticeReads(
                        ClasspathSourceCatalogRegistry.CURRENT_VERSION.value(),
                        List.of(
                                new PracticeReadSource(new SourceKind("scm.pull-request.core"), ReadNeed.REQUIRED),
                                new PracticeReadSource(new SourceKind("scm.pull-request.diff"), ReadNeed.COMPLETE),
                                new PracticeReadSource(new SourceKind("scm.repository.tree"), ReadNeed.OPTIONAL))),
                base.reviewedBy(),
                base.criteria(),
                base.script(),
                base.scan(),
                base.lineNotes(),
                base.guidance());
        Practice practice = runtimePracticeVersions.create(new PracticeVersionService.NewPractice(
                workspace, SLUG, new ItemKind("scm.pull_request"), definition, null, 0, null));
        practice.setFeedbackMode(FeedbackMode.AUTOMATIC);
        return practiceRepository.saveAndFlush(practice);
    }

    private ReviewJob runningJob(Workspace workspace, Practice practice, long actor) {
        var binding = runtimeModelRouting
                .binding(workspace.getId(), AgentPurpose.PRACTICE_REVIEW, actor)
                .orElseThrow();
        Map<ModelKind, FrozenModel> slots = new EnumMap<>(ModelKind.class);
        slots.put(
                ModelKind.CHAT,
                FrozenModel.from(binding, resolver)
                        .withPriceSnapshot(admission.admit(binding).price()));
        for (AgentPurpose purpose : AgentPurpose.practiceScript())
            runtimeModelRouting
                    .practiceScriptBinding(workspace.getId(), purpose, actor)
                    .ifPresent(selected -> slots.put(
                            purpose.kind(),
                            FrozenModel.from(selected, resolver)
                                    .withPriceSnapshot(admission.admit(selected).price())));
        var job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());
        job.setInput(new ReviewJobInput(1, List.of(new ReviewAssignmentId(job.getReviewId(), practice.getId(), 1))));
        job.setConfigSnapshot(ConfigSnapshot.from(binding, resolver)
                .withPriceSnapshot(admission.admit(binding).price())
                .withPracticeScript(slots)
                .toJson(objectMapper));
        job.setCapture(EvidenceSnapshotFixtures.snapshot(objectMapper));
        return job;
    }

    private ReviewJob capture(ReviewJob job, Path workDir, User developer, Workspace workspace)
            throws IOException, InterruptedException {
        Path context = workDir.resolve(SandboxLayout.CONTEXT_PREFIX);
        JsonNode change = objectMapper.readTree(context.resolve("change.json").toFile());
        String base = change.path("base_sha").asString();
        String head = change.path("head_sha").asString();
        Repository repository = new Repository();
        repository.setNativeId(System.nanoTime());
        repository.setProvider(ensureGitHubProvider());
        repository.setName("fixture");
        repository.setNameWithOwner("test/fixture");
        repository.setHtmlUrl("https://github.com/test/fixture");
        repository.setDefaultBranch("main");
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository = repositories.save(repository);
        var monitor = WorkspaceTestFixtures.repositoryMonitor(workspace, repository.getNameWithOwner());
        monitor.setNativeId(repository.getNativeId());
        monitors.saveAndFlush(monitor);
        Instant now = Instant.now();
        pullRequests.upsertCore(
                System.nanoTime(),
                Objects.requireNonNull(ensureGitHubProvider().getId()),
                1,
                TITLE,
                BODY,
                "OPEN",
                null,
                "https://github.com/test/fixture/pull/1",
                false,
                null,
                0,
                now,
                now,
                now,
                developer.getId(),
                repository.getId(),
                null,
                null,
                false,
                false,
                1,
                10,
                0,
                1,
                null,
                null,
                null,
                SOURCE_BRANCH,
                "main",
                head,
                base,
                null,
                null);
        long pullRequestId = Objects.requireNonNull(pullRequests
                .findByRepositoryIdAndNumber(repository.getId(), 1)
                .orElseThrow()
                .getId());
        var capturedWork = LiveWorkRecords.capturedPullRequest(
                pullRequestId, Objects.requireNonNull(repository.getId()), 1, head, TITLE, BODY, SOURCE_BRANCH);
        @Nullable Instant admittedAt = job.getAvailableAt();
        byte[] record = Objects.requireNonNull(
                transactions.execute(status -> LiveWorkRecords.staged(PullRequestContentSource.workRecord(
                        pullRequests.findByIdForReviewContext(pullRequestId).orElseThrow(),
                        capturedWork,
                        admittedAt))));
        Files.write(context.resolve("metadata.json"), record);

        // One capture for both sides: the job's snapshot, and the folder index the runner reads.
        Map<String, byte[]> files = new LinkedHashMap<>();
        Map<String, Path> onDisk = new LinkedHashMap<>();
        ObjectNode snapshot = (ObjectNode) Objects.requireNonNull(job.getCapture());
        snapshot.putArray("directories").add(SandboxLayout.REPO_MOUNT_RELATIVE);
        Map<String, ObjectNode> sources = new LinkedHashMap<>();
        Map<String, String> identities = Map.of(
                "scm.pull-request.diff",
                base + ":" + head,
                "scm.repository.tree",
                LiveRunnerWorkspace.treeIdentity(workDir));
        for (Map.Entry<String, String> captured :
                LiveRunnerWorkspace.capturedFiles().entrySet()) {
            String path = captured.getKey();
            String kind = captured.getValue();
            ObjectNode source = sources.computeIfAbsent(
                    kind, owner -> EvidenceSnapshotFixtures.availableSource(snapshot, owner, identities.get(owner)));
            byte[] bytes = Files.readAllBytes(workDir.resolve(path));
            if (kind.equals("scm.repository.tree")) onDisk.put(path, workDir.resolve(path));
            else files.put(path, bytes);
            EvidenceSnapshotFixtures.file(snapshot, source, path, ProvenanceDigest.sha256Hex(bytes))
                    .put("mediaType", path.endsWith(".json") ? "application/json" : "text/plain")
                    .put("bytes", bytes.length);
        }

        var texts = snapshot.withObject("manifest").withArray("citableTexts");
        for (var entry : LiveRunnerWorkspace.capturedFiles().entrySet()) {
            String path = entry.getKey();
            String kind = entry.getValue();
            var text = texts.addObject()
                    .put("source", kind)
                    .put("path", path)
                    .put("side", "NEW")
                    .put("stagedPath", path)
                    .put("sha256", ProvenanceDigest.sha256Hex(Files.readAllBytes(workDir.resolve(path))));
            if (kind.equals("scm.repository.tree")) text.put("commit", head);
        }
        Files.write(
                workDir.resolve(SandboxLayout.MANIFEST_PATH),
                objectMapper.writeValueAsBytes(snapshot.path("manifest")));

        var holder = itemWriter.upsert(new ItemRegistration(
                job.getWorkspaceId(), new ItemKind("scm.repository"), repository.getId(), null, null));
        var item = itemWriter.upsert(new ItemRegistration(
                job.getWorkspaceId(), new ItemKind("scm.pull_request"), pullRequestId, holder.itemId(), null));
        var source = itemSources.stream()
                .filter(candidate -> candidate.supports(item))
                .findFirst()
                .orElseThrow();
        var pinned = source.current(item).orElseThrow();
        long actor = accountId(developer);
        var reviewId = reviews.request(new ReviewRequest(
                job.getWorkspaceId(),
                item.itemId(),
                ReviewKind.ITEM,
                pinned.version(),
                ReviewOrigin.REQUEST,
                null,
                actor,
                null,
                null,
                null,
                null,
                List.of(job.getInput().assignments().getFirst().practiceId()),
                false));
        var review = reviews.find(job.getWorkspaceId(), reviewId).orElseThrow();
        UUID jobId = reviewQueue.enqueue(
                review,
                item.itemId(),
                review.practices().stream()
                        .map(practice -> new ReviewAssignmentId(reviewId, practice.practiceId(), item.itemId()))
                        .toList());
        var priced = job.getConfigSnapshot();
        job = jobRepository.findByIdAndWorkspaceId(jobId, item.workspaceId()).orElseThrow();
        job.setConfigSnapshot(priced);
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setWorkerId(workerProperties.resolvedWorkerId());
        job.setStartedAt(Instant.now());
        job.setExecutionStartedAt(Instant.now());
        job.setCapture(snapshot);
        reviews.start(item.workspaceId(), reviewId);
        job = jobRepository.saveAndFlush(job);
        prepared.add(PreparedJobInputsFixtures.prepare(
                evidenceFiles,
                job,
                PreparedJobInputsFixtures.inputs(
                        new PreparedEvidence(
                                files,
                                onDisk,
                                List.of(),
                                null,
                                List.of(new EvidenceDirectory(
                                        SandboxLayout.REPO_MOUNT_RELATIVE,
                                        workDir.resolve(SandboxLayout.REPO_MOUNT_RELATIVE)))),
                        null)));
        return jobRepository.findById(job.getId()).orElseThrow();
    }

    // Warm self-hosted models before script deadlines; model startup can exceed the deadline.
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

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path temporaryDirectory(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
