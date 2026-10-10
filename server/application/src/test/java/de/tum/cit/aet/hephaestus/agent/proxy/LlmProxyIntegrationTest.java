package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmAuthMode;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver.ConnectionRef;
import de.tum.cit.aet.hephaestus.agent.catalog.ModelKind;
import de.tum.cit.aet.hephaestus.agent.catalog.ModelVisibility;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.agent.job.PrecomputeKindTotal;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.agent.usage.PricingState;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtVerifier;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class LlmProxyIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String PRACTICE = "comment-quality";

    @Autowired
    private AgentJobRepository jobRepository;

    @Autowired
    private WorkspaceAgentBindingRepository agentBindingRepository;

    @Autowired
    private LlmConnectionRepository connectionRepository;

    @Autowired
    private LlmModelRepository modelRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LlmModelResolver modelResolver;

    @Autowired
    private WorkerJwtVerifier jwtVerifier;

    @Autowired
    private WorkerJwtIssuer jwtIssuer;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    private JobTokenAuthenticationFilter filter;
    private Workspace workspace;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        meterRegistry = new SimpleMeterRegistry();
        filter = new JobTokenAuthenticationFilter(
                jobRepository, jwtVerifier, new MentorProxyCredentialRegistry(), objectMapper, "worker-1");
        User owner = persistUser("proxy-owner");
        workspace = createWorkspace("proxy-ws", "Proxy Workspace", "proxy-org", AccountType.ORG, owner);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldAuthenticateRunningJobTokenAgainstPersistedRouting() throws Exception {
        AgentJob job = runningJob(true);
        AuthenticationResult result = authenticate(
                jwtIssuer.issueForJob(job.getId(), workspace.getId(), job.getRetryCount(), Duration.ofMinutes(5)));

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.authentication())
                .isNotNull()
                .extracting(Authentication::getPrincipal)
                .isInstanceOf(ProxyRouting.class);
    }

    @Test
    void shouldRejectLegacyOpaqueJobToken() throws Exception {
        // The database-backed secret the entity still persists is a pre-migration credential; the
        // proxy accepts only per-job JWTs — there is deliberately no dual-accept window.
        AgentJob job = runningJob(true);

        AuthenticationResult result = authenticate(job.getJobToken());

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.authentication()).isNull();
    }

    @Test
    void shouldRejectWorkerSessionJwtAtTheProxy() throws Exception {
        runningJob(true);

        AuthenticationResult result = authenticate(jwtIssuer.issue("worker-1").token());

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.authentication()).isNull();
    }

    @Test
    void shouldRejectJobJwtBoundToAnotherWorkspace() throws Exception {
        AgentJob job = runningJob(true);

        AuthenticationResult result = authenticate(
                jwtIssuer.issueForJob(job.getId(), workspace.getId() + 1, job.getRetryCount(), Duration.ofMinutes(5)));

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.authentication()).isNull();
    }

    /**
     * Two sandboxes share a worker, so one holding the other's token must not become the other: the
     * route and the execution it bills both come from the row the token names.
     */
    @Test
    void shouldRouteEachJobTokenOnlyToTheJobThatMintedIt() throws Exception {
        AgentJob first = runningJob(true);
        AgentJob second = runningJob(true);

        ProxyRouting firstRouting = routingOf(authenticate(tokenFor(first)));
        ProxyRouting secondRouting = routingOf(authenticate(tokenFor(second)));

        assertThat(firstRouting.principalDescription()).isEqualTo("job:" + first.getId());
        assertThat(firstRouting.sourceId()).isEqualTo(first.getId());
        assertThat(secondRouting.principalDescription()).isEqualTo("job:" + second.getId());
        assertThat(secondRouting.sourceId()).isEqualTo(second.getId());
    }

    /**
     * A requeue starts a new attempt with its own token, so the one the dead attempt handed its
     * sandbox has to stop working — otherwise an orphaned container keeps spending on a row that has
     * already been re-admitted.
     */
    @Test
    void shouldRejectAJobTokenMintedForAnEarlierAttempt() throws Exception {
        AgentJob job = runningJob(true);
        String earlierAttempt = tokenFor(job);
        job.setRetryCount(job.getRetryCount() + 1);
        jobRepository.save(job);

        AuthenticationResult result = authenticate(earlierAttempt);

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.authentication()).isNull();
    }

    @Test
    void shouldFailClosedWhenCatalogModelIsDisabled() {
        AgentJob job = runningJob(false);
        long connectionId = job.getConfigSnapshot().get("connectionId").asLong();
        long modelId = job.getConfigSnapshot().get("modelId").asLong();

        assertThat(modelResolver.resolveProxyCredential(
                        new ConnectionRef(FundingSource.INSTANCE, connectionId, modelId, workspace.getId())))
                .isNull();
    }

    @ParameterizedTest
    @CsvSource({"GET,'',401", "GET,/workspace,401", "GET,/frames,401", "POST,/result,409"})
    void shouldAuthorizeRuntimeRoutesOnlyForThePersistedWorkerAndAttempt(
            String method, String suffix, int nonOwnerStatus) throws Exception {
        AgentJob job = runningJob(true);
        String token = tokenFor(job);
        String path = "/internal/llm/runtime/" + job.getId() + suffix;
        assertThat(authenticate(method, path, token).status()).isEqualTo(200);
        assertThat(authenticate(method, "/internal/llm/runtime/" + UUID.randomUUID() + suffix, token)
                        .status())
                .isEqualTo(401);

        job.setWorkerId("another-worker");
        jobRepository.save(job);
        assertThat(authenticate(method, path, token).status()).isEqualTo(nonOwnerStatus);

        job.setWorkerId("worker-1");
        job.setRetryCount(job.getRetryCount() + 1);
        jobRepository.save(job);
        assertThat(authenticate(method, path, token).status()).isEqualTo(401);
    }

    /** Each op reaches the upstream path of its protocol, with the catalog model and credential. */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "CHAT|/internal/llm/precompute/chat/chat/completions|/v1/chat/completions|"
                        + "{\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":4}}|11|4",
                "DECISION|/internal/llm/precompute/decision/decisions|/v1/decisions|"
                        + "{\"answers\":[],\"usage\":{\"input_tokens\":40,\"output_tokens\":3}}|40|3",
                "EMBEDDING|/internal/llm/precompute/embedding/embeddings|/v1/embeddings|"
                        + "{\"data\":[],\"usage\":{\"prompt_tokens\":12,\"total_tokens\":12}}|12|0",
                "RERANKING|/internal/llm/precompute/reranking/rerank|/v1/rerank|{\"results\":[]}|0|0"
            })
    void shouldForwardEachPrecomputeOpWithTheCatalogModelWhenThePrecomputeTokenCallsIt(
            ModelKind slot, String path, String upstreamPath, String reply, long input, long output) throws Exception {
        try (MockWebServer upstream = new MockWebServer()) {
            upstream.start();
            upstream.enqueue(json(reply));
            AgentJob job = precomputeJob(upstream.url("/v1").toString());

            ProxyResult result = callPrecompute(job, path, "{\"model\":\"runner-controlled\",\"input\":[\"a\"]}");

            assertThat(result.status()).isEqualTo(200);
            RecordedRequest sent = Objects.requireNonNull(upstream.takeRequest(5, TimeUnit.SECONDS));
            assertThat(sent.getUrl().encodedPath()).isEqualTo(upstreamPath);
            assertThat(sent.getHeaders().get("Authorization")).isEqualTo("Bearer upstream-secret");
            assertThat(objectMapper
                            .readTree(Objects.requireNonNull(sent.getBody()).utf8())
                            .path("model")
                            .asString())
                    .isEqualTo(slot.name().toLowerCase(Locale.ROOT) + "-upstream");
            assertThat(precomputeRows(job, 0)).containsExactly(new UsageRow(slot, PRACTICE, 1, input, output));
            // The row keeps the tier of the model that served it, so the review can show it for a run that did not
            // finish.
            assertThat(jdbc.queryForObject(
                            "SELECT data_handling_tier FROM agent_job_precompute_usage WHERE job_id = ?",
                            String.class,
                            job.getId()))
                    .isEqualTo(slot == ModelKind.CHAT ? "UNDECLARED" : "CLOUD");
            AgentJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
            // The review's ledger row bills the chat model, so only chat calls reach the job's counters.
            assertThat(Objects.requireNonNullElse(reloaded.getLlmTotalCalls(), 0))
                    .isEqualTo(slot == ModelKind.CHAT ? 1 : 0);
        }
    }

    @Test
    void shouldRefuseWithPaymentRequiredWhenTheAttemptsPrecomputeTokensReachTheCap() throws Exception {
        try (MockWebServer upstream = new MockWebServer()) {
            upstream.start();
            upstream.enqueue(json("{\"data\":[],\"usage\":{\"prompt_tokens\":120}}"));
            AgentJob job = precomputeJob(upstream.url("/v1").toString());
            LlmProxyService capped = proxyService(100);

            ProxyResult first =
                    callPrecompute(capped, job, "/internal/llm/precompute/embedding/embeddings", PRACTICE, "{}");
            ProxyResult second =
                    callPrecompute(capped, job, "/internal/llm/precompute/decision/decisions", PRACTICE, "{}");

            assertThat(first.status()).isEqualTo(200);
            assertThat(second.status()).isEqualTo(402);
            assertThat(second.body()).isEqualTo("Precompute budget spent");
            assertThat(upstream.getRequestCount()).isEqualTo(1);
        }
    }

    /** Each practice keeps its own row, and the cap judges the attempt over all of them. */
    @Test
    void shouldCountEachPracticeApartAndCapThemTogetherWhenTwoPracticesCallOneModel() throws Exception {
        try (MockWebServer upstream = new MockWebServer()) {
            upstream.start();
            for (int call = 0; call < 3; call++) {
                upstream.enqueue(json("{\"data\":[],\"usage\":{\"prompt_tokens\":60}}"));
            }
            AgentJob job = precomputeJob(upstream.url("/v1").toString());
            LlmProxyService capped = proxyService(100);
            String path = "/internal/llm/precompute/embedding/embeddings";

            ProxyResult first = callPrecompute(capped, job, path, PRACTICE, "{}");
            ProxyResult second = callPrecompute(capped, job, path, "test-coverage", "{}");
            ProxyResult third = callPrecompute(capped, job, path, "test-coverage", "{}");

            assertThat(first.status()).isEqualTo(200);
            assertThat(second.status()).isEqualTo(200);
            assertThat(third.status()).isEqualTo(402);
            assertThat(precomputeRows(job, 0))
                    .containsExactlyInAnyOrder(
                            new UsageRow(ModelKind.EMBEDDING, PRACTICE, 1, 60, 0),
                            new UsageRow(ModelKind.EMBEDDING, "test-coverage", 1, 60, 0));
            assertThat(jobRepository.sumPrecomputeUsageByKind(workspace.getId(), job.getId(), 0))
                    .containsExactly(new PrecomputeKindTotal(ModelKind.EMBEDDING, 2, 120, 0));
        }
    }

    /** The attempt in the key is the fence: a late call of an earlier attempt adds to no row. */
    @Test
    void shouldKeepEachAttemptsPrecomputeRowApartWhenTheJobIsRequeued() {
        AgentJob job = precomputeJob("https://models.example.com/v1");
        ProxyUsageAccumulator accumulator = accumulator();
        var attemptZero = billed(job, 0, LlmUsageSourceType.PRECOMPUTE_DECISION);
        var decision = new PrecomputeCall(ModelKind.DECISION, null, PRACTICE);
        accumulator.accumulatePrecompute(attemptZero, decision, new ProxyTokenUsage(10, 2, 0, 0, 0));
        accumulator.accumulatePrecompute(attemptZero, decision, new ProxyTokenUsage(5, 1, 0, 0, 0));

        job.setRetryCount(1);
        job = jobRepository.saveAndFlush(job);
        accumulator.accumulatePrecompute(attemptZero, decision, new ProxyTokenUsage(1000, 0, 0, 0, 0));
        accumulator.accumulatePrecompute(
                billed(job, 1, LlmUsageSourceType.PRECOMPUTE_DECISION), decision, new ProxyTokenUsage(7, 0, 0, 0, 0));

        assertThat(precomputeRows(job, 0)).containsExactly(new UsageRow(ModelKind.DECISION, PRACTICE, 2, 15, 3));
        assertThat(precomputeRows(job, 1)).containsExactly(new UsageRow(ModelKind.DECISION, PRACTICE, 1, 7, 0));
    }

    @Test
    void shouldDropAPrecomputeCallWhenItReturnsAfterTheJobEnded() {
        AgentJob job = precomputeJob("https://models.example.com/v1");
        job.setStatus(AgentJobStatus.COMPLETED);
        job = jobRepository.saveAndFlush(job);

        accumulator()
                .accumulatePrecompute(
                        billed(job, 0, LlmUsageSourceType.PRECOMPUTE_EMBEDDING),
                        new PrecomputeCall(ModelKind.EMBEDDING, null, PRACTICE),
                        new ProxyTokenUsage(9, 0, 0, 0, 0));

        assertThat(precomputeRows(job, 0)).isEmpty();
    }

    private ProxyResult callPrecompute(AgentJob job, String path, String body) throws Exception {
        return callPrecompute(proxyService(200_000), job, path, PRACTICE, body);
    }

    /** @param practice the practice that the runner names in its header */
    private ProxyResult callPrecompute(LlmProxyService service, AgentJob job, String path, String practice, String body)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Authorization", "Bearer " + precomputeTokenFor(job));
        HttpHeaders headers = new HttpHeaders();
        headers.set(JobTokenAuthenticationFilter.PRECOMPUTE_PRACTICE_HEADER, practice);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<@Nullable ResponseEntity<?>> served = new AtomicReference<>();
        filter.doFilter(
                request,
                response,
                (filteredRequest, filteredResponse) -> served.set(
                        service.proxyPrecompute(request, response, headers, body.getBytes(StandardCharsets.UTF_8))));
        ResponseEntity<?> result = served.get();
        if (result == null) return new ProxyResult(response.getStatus(), null);
        Object payload = result.getBody();
        return new ProxyResult(
                result.getStatusCode().value(),
                payload instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(payload));
    }

    private record ProxyResult(int status, @Nullable String body) {}

    /**
     * The worker role is off in this context, so the proxy is assembled here around the real catalog and
     * repositories. Its accumulator is not a Spring bean here, so {@link #transactionTemplate} supplies
     * the transaction that its {@code REQUIRES_NEW} boundary gives it in production.
     */
    private LlmProxyService proxyService(long maxTokensPerAttempt) {
        ProxyRequestPolicy policy = mock(ProxyRequestPolicy.class);
        when(policy.allows(any())).thenReturn(true);
        return new LlmProxyService(
                Tracer.NOOP,
                WebClient.create(),
                modelResolver,
                objectMapper,
                new ProxyAccounting(
                        new ProxyBudgetGate(
                                mock(LlmBudgetService.class),
                                new PracticeReviewProperties(false, 15, 5, null, 12, 16000, maxTokensPerAttempt)),
                        accumulator(),
                        mock(MentorTurnUsageAccumulator.class),
                        meterRegistry,
                        objectMapper),
                policy);
    }

    private ProxyUsageAccumulator accumulator() {
        return new ProxyUsageAccumulator(jobRepository, new SimpleMeterRegistry()) {
            @Override
            public void accumulate(ProxyRouting.@Nullable BilledAttempt attempt, @Nullable ProxyTokenUsage usage) {
                transactionTemplate.executeWithoutResult(tx -> super.accumulate(attempt, usage));
            }

            @Override
            public void accumulatePrecompute(
                    ProxyRouting.BilledAttempt attempt, PrecomputeCall call, @Nullable ProxyTokenUsage usage) {
                transactionTemplate.executeWithoutResult(tx -> super.accumulatePrecompute(attempt, call, usage));
            }
        };
    }

    private static ProxyRouting.BilledAttempt billed(AgentJob job, int attempt, LlmUsageSourceType sourceType) {
        return new ProxyRouting.BilledAttempt(sourceType, job.getId(), attempt, BigDecimal.ZERO, "worker-1");
    }

    private List<UsageRow> precomputeRows(AgentJob job, int attempt) {
        return jdbc.query(
                "SELECT model_kind, practice_slug, calls, input_tokens, output_tokens "
                        + "FROM agent_job_precompute_usage WHERE job_id = ? AND attempt = ?",
                (row, index) -> new UsageRow(
                        ModelKind.valueOf(row.getString("model_kind")),
                        row.getString("practice_slug"),
                        row.getInt("calls"),
                        row.getLong("input_tokens"),
                        row.getLong("output_tokens")),
                job.getId(),
                attempt);
    }

    private record UsageRow(ModelKind kind, String practice, int calls, long inputTokens, long outputTokens) {}

    private static MockResponse json(String body) {
        return new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build();
    }

    private String precomputeTokenFor(AgentJob job) {
        return jwtIssuer.issueForJobUntil(
                job.getId(),
                workspace.getId(),
                job.getRetryCount(),
                Instant.now().plus(Duration.ofMinutes(5)),
                WorkerJwtIssuer.LLM_PRECOMPUTE_SCOPE);
    }

    /** A running review whose four slots each point at their own catalog model behind {@code baseUrl}. */
    private AgentJob precomputeJob(String baseUrl) {
        Map<ModelKind, CatalogModel> catalog = new EnumMap<>(ModelKind.class);
        for (ModelKind kind : ModelKind.values()) {
            LlmApiProtocol protocol =
                    switch (kind) {
                        case CHAT -> LlmApiProtocol.OPENAI_COMPLETIONS;
                        case DECISION -> LlmApiProtocol.OPENAI_DECISIONS;
                        case EMBEDDING -> LlmApiProtocol.OPENAI_EMBEDDINGS;
                        case RERANKING -> LlmApiProtocol.COHERE_RERANK;
                    };
            String name = kind.name().toLowerCase(Locale.ROOT);
            LlmConnection connection = LlmCatalogTestFixtures.connection(name + "-" + System.nanoTime());
            connection.setBaseUrl(baseUrl);
            connection.setApiProtocol(protocol);
            connection.setAuthMode(LlmAuthMode.BEARER);
            connection.setApiKey("upstream-secret");
            connection = connectionRepository.save(connection);
            LlmModel model = modelRepository.save(
                    LlmCatalogTestFixtures.model(connection, name + "-" + System.nanoTime(), name + "-upstream"));
            catalog.put(kind, new CatalogModel(protocol.wire(), connection.getId(), model.getId()));
        }
        LlmPriceSnapshot price = new LlmPriceSnapshot(
                FundingSource.INSTANCE, PricingState.PRICED, null, null, BigDecimal.ONE, BigDecimal.ONE, null, null);
        Map<ModelKind, FrozenModel> slots = new EnumMap<>(ModelKind.class);
        for (ModelKind kind : new ModelKind[] {ModelKind.DECISION, ModelKind.EMBEDDING, ModelKind.RERANKING}) {
            CatalogModel model = Objects.requireNonNull(catalog.get(kind));
            slots.put(
                    kind,
                    new FrozenModel(
                            model.protocol(),
                            baseUrl,
                            kind.name().toLowerCase(Locale.ROOT) + "-upstream",
                            FundingSource.INSTANCE,
                            model.connectionId(),
                            model.modelId(),
                            workspace.getId(),
                            DataHandlingTier.CLOUD,
                            null,
                            price));
        }
        CatalogModel chat = Objects.requireNonNull(catalog.get(ModelKind.CHAT));
        ConfigSnapshot snapshot = new ConfigSnapshot(
                        ConfigSnapshot.SCHEMA_VERSION,
                        chat.protocol(),
                        baseUrl,
                        "chat-upstream",
                        null,
                        null,
                        null,
                        null,
                        FundingSource.INSTANCE,
                        chat.connectionId(),
                        chat.modelId(),
                        workspace.getId(),
                        600,
                        false,
                        price,
                        DataHandlingTier.UNDECLARED,
                        null)
                .withPrecompute(slots);

        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setStatus(AgentJobStatus.RUNNING);
        job.setWorkerId("worker-1");
        job.setConfigSnapshot(snapshot.toJson(objectMapper));
        return jobRepository.saveAndFlush(job);
    }

    private record CatalogModel(String protocol, Long connectionId, Long modelId) {}

    private AuthenticationResult authenticate(String token) throws Exception {
        return authenticate("POST", "/internal/llm/chat/completions", token);
    }

    private AuthenticationResult authenticate(String method, String path, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Authentication> authentication = new AtomicReference<>();
        filter.doFilter(
                request,
                response,
                (filteredRequest, filteredResponse) -> authentication.set(Objects.requireNonNull(
                        SecurityContextHolder.getContext().getAuthentication())));
        return new AuthenticationResult(response.getStatus(), authentication.get());
    }

    private record AuthenticationResult(int status, Authentication authentication) {}

    private String tokenFor(AgentJob job) {
        return jwtIssuer.issueForJob(job.getId(), workspace.getId(), job.getRetryCount(), Duration.ofMinutes(5));
    }

    private static ProxyRouting routingOf(AuthenticationResult result) {
        Authentication authentication = Objects.requireNonNull(result.authentication(), "token did not authenticate");
        return (ProxyRouting) Objects.requireNonNull(authentication.getPrincipal(), "no routing on the principal");
    }

    private AgentJob runningJob(boolean modelEnabled) {
        LlmConnection connection = LlmCatalogTestFixtures.connection("connection-" + System.nanoTime());
        connection.setBaseUrl("https://api.example.com/v1");
        connection.setApiProtocol(LlmApiProtocol.OPENAI_COMPLETIONS);
        connection.setAuthMode(LlmAuthMode.BEARER);
        connection.setApiKey("upstream-secret");
        connection = connectionRepository.save(connection);

        LlmModel model = modelRepository.save(LlmCatalogTestFixtures.model(
                connection, "model-" + System.nanoTime(), "catalog-model", ModelVisibility.PUBLIC, modelEnabled));
        // A workspace binds one model per purpose, so a second job re-points the binding it already has.
        WorkspaceAgentBinding binding = agentBindingRepository
                .findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        workspace.getId(), AgentPurpose.PRACTICE_REVIEW, DataHandlingTier.UNDECLARED)
                .orElseGet(WorkspaceAgentBinding::new);
        binding.setWorkspace(workspace);
        binding.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        binding.setInstanceModel(model);
        binding.setEnabled(true);
        binding.setTimeoutSeconds(600);
        agentBindingRepository.save(binding);

        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("schemaVersion", 1);
        snapshot.put("apiProtocol", "openai-completions");
        snapshot.put("baseUrl", connection.getBaseUrl());
        snapshot.put("upstreamModelId", model.getUpstreamModelId());
        snapshot.put("connectionScope", "INSTANCE");
        snapshot.put("fundingSource", "INSTANCE");
        snapshot.put("connectionId", connection.getId());
        snapshot.put("modelId", model.getId());
        snapshot.put("workspaceId", workspace.getId());
        snapshot.put("timeoutSeconds", 600);
        snapshot.put("allowInternet", false);

        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setStatus(AgentJobStatus.RUNNING);
        job.setWorkerId("worker-1");
        job.setConfigSnapshot(snapshot);
        return jobRepository.save(job);
    }
}
