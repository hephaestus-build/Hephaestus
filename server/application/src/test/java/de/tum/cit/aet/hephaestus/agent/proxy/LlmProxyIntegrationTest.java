package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptSigningKey;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmAuthMode;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPool;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionHealth;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver.ConnectionRef;
import de.tum.cit.aet.hephaestus.agent.catalog.ModelVisibility;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.job.FrozenCharge;
import de.tum.cit.aet.hephaestus.agent.job.PracticeScriptKindTotal;
import de.tum.cit.aet.hephaestus.agent.job.ReviewAttemptUsageService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.agent.job.TerminalUsageTestFixtures;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.ObjectMapper;

class LlmProxyIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String PRACTICE = "comment-quality";

    @Autowired
    private ReviewJobRepository jobRepository;

    @Autowired
    private MentorTurnRepository turnRepository;

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

    /** This test's worker key: the worker role, which signs attempt tokens, is off in the test context. */
    private static final AttemptSigningKey KEY = AttemptSigningKey.generate();

    private final AttemptTokenVerifier jwtVerifier = new AttemptTokenVerifier(KEY, new SimpleMeterRegistry());

    private final AttemptTokenIssuer jwtIssuer = new AttemptTokenIssuer(KEY);

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LlmUsageRecorder usageRecorder;

    @Autowired
    private ReviewAttemptUsageService attemptUsage;

    @Autowired
    private ReviewSnapshotQuery reviewSnapshots;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private LlmCallPoolRepository callPools;

    @Autowired
    private LlmCallPermitRepository callRows;

    private JobTokenAuthenticationFilter filter;
    private Workspace workspace;
    private SimpleMeterRegistry meterRegistry;

    @Autowired
    private PracticeVersionService versions;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        meterRegistry = new SimpleMeterRegistry();
        filter = new JobTokenAuthenticationFilter(
                jobRepository, turnRepository, new SandboxGatewaySessions(), jwtVerifier, "worker-1");
        User owner = persistUser("proxy-owner");
        workspace = createWorkspace("proxy-ws", "Proxy Workspace", "proxy-org", AccountType.ORG, owner);
        for (String slug : List.of(PRACTICE, "test-coverage")) {
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
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldAuthenticateRunningJobTokenAgainstPersistedRouting() throws Exception {
        ReviewJob job = runningJob(true);
        AuthenticationResult result = authenticate(tokenFor(job));

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.authentication())
                .isNotNull()
                .extracting(Authentication::getPrincipal)
                .isInstanceOf(ProxyIdentity.class);
    }

    @Test
    void shouldRejectLegacyOpaqueJobToken() throws Exception {
        // Only scoped per-attempt worker JWTs authorize the proxy.
        runningJob(true);

        AuthenticationResult result = authenticate("retired-opaque-token");

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.authentication()).isNull();
    }

    @Test
    void shouldRejectAnAttemptTokenThatAnotherWorkerSigned() throws Exception {
        ReviewJob job = runningJob(true);
        Instant work = Instant.now().plus(Duration.ofMinutes(5));

        AuthenticationResult result = authenticate(new AttemptTokenIssuer(AttemptSigningKey.generate())
                .issueForAttempt(
                        AttemptJwt.Kind.REVIEW,
                        job.getId(),
                        job.getWorkspaceId(),
                        job.getAttempt(),
                        work,
                        work.plus(Duration.ofMinutes(10))));

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.authentication()).isNull();
    }

    @Test
    void shouldRejectJobJwtBoundToAnotherWorkspace() throws Exception {
        ReviewJob job = runningJob(true);

        Instant work = Instant.now().plus(Duration.ofMinutes(5));
        AuthenticationResult result = authenticate(jwtIssuer.issueForAttempt(
                AttemptJwt.Kind.REVIEW,
                job.getId(),
                workspace.getId() + 1,
                job.getAttempt(),
                work,
                work.plus(Duration.ofMinutes(10))));

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.authentication()).isNull();
    }

    /**
     * Two sandboxes share a worker, so one holding the other's token must not become the other: the
     * route and the execution it bills both come from the row the token names.
     */
    @Test
    void shouldRouteEachJobTokenOnlyToTheJobThatMintedIt() throws Exception {
        ReviewJob first = runningJob(true);
        ReviewJob second = runningJob(true);

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
        ReviewJob job = runningJob(true);
        String earlierAttempt = tokenFor(job);
        job.setAttempt(job.getAttempt() + 1);
        jobRepository.save(RuntimeJobTestData.persistParent(jdbc, job));

        AuthenticationResult result = authenticate(earlierAttempt);

        assertThat(result.status()).isEqualTo(409);
        assertThat(result.authentication()).isNull();
    }

    @Test
    void shouldFailClosedWhenCatalogModelIsDisabled() {
        ReviewJob job = runningJob(false);
        long connectionId =
                job.getConfigSnapshot().path("model").get("connectionId").asLong();
        long modelId = job.getConfigSnapshot().path("model").get("modelId").asLong();

        assertThat(modelResolver.resolveProxyCredential(
                        new ConnectionRef(UsageFundingSource.INSTANCE, connectionId, modelId, workspace.getId())))
                .isNull();
    }

    @ParameterizedTest
    @CsvSource({"GET,''", "GET,/workspace", "GET,/frames", "POST,/result"})
    void shouldAuthorizeRuntimeRoutesOnlyForThePersistedWorkerAndAttempt(String method, String suffix)
            throws Exception {
        ReviewJob job = runningJob(true);
        String token = tokenFor(job);
        String path = "/internal/llm/runtime/" + job.getId() + suffix;
        assertThat(authenticate(method, path, token).status()).isEqualTo(200);
        assertThat(authenticate(method, "/internal/llm/runtime/" + UUID.randomUUID() + suffix, token)
                        .status())
                .isEqualTo(401);

        job.setWorkerId("another-worker");
        jobRepository.save(RuntimeJobTestData.persistParent(jdbc, job));
        assertThat(authenticate(method, path, token).status()).isEqualTo(409);

        job.setWorkerId("worker-1");
        job.setAttempt(job.getAttempt() + 1);
        jobRepository.save(RuntimeJobTestData.persistParent(jdbc, job));
        assertThat(authenticate(method, path, token).status()).isEqualTo(409);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/internal/llm/chat/completions",
                "/internal/llm/practice-script/decision/decisions",
                "/internal/llm/admit-results"
            })
    void shouldAuthenticateTheJobsOneTokenWhenItCallsAnyModelRouteOrAdmission(String path) throws Exception {
        ReviewJob job = practiceScriptJob("https://models.example.com/v1");

        AuthenticationResult result = authenticate("POST", path, tokenFor(job));

        assertThat(result.status()).isEqualTo(200);
        var authentication = Objects.requireNonNull(result.authentication());
        assertThat(((ProxyIdentity) Objects.requireNonNull(authentication.getPrincipal())).description())
                .isEqualTo("job:" + job.getId());
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("SCOPE_" + AttemptTokenIssuer.LLM_PROXY_SCOPE);
    }

    @Test
    void shouldReadMovedPoolMembershipWhenTheClaimAlreadyLoadedTheModel() {
        ReviewJob job = practiceScriptJob("https://models.example.com/v1");
        var snapshot = ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper);
        var first = new LlmCallPool();
        first.setSlug("first-" + UUID.randomUUID());
        first.setDisplayName("First");
        first.setMaxConcurrentCalls(4);
        first = callPools.saveAndFlush(first);
        var second = new LlmCallPool();
        second.setSlug("second-" + UUID.randomUUID());
        second.setDisplayName("Second");
        second.setMaxConcurrentCalls(4);
        second = callPools.saveAndFlush(second);
        long firstId = first.getId();
        long secondId = second.getId();
        long modelId = Objects.requireNonNull(snapshot.model().modelId());
        jdbc.update("UPDATE llm_model SET call_pool_id=? WHERE id=?", firstId, modelId);
        transactionTemplate.executeWithoutResult(status -> {
            var cached = modelRepository.findById(modelId).orElseThrow();
            assertThat(cached.getCallPoolId()).isEqualTo(firstId);
            jdbc.update("UPDATE llm_model SET call_pool_id=? WHERE id=?", secondId, modelId);
            assertThat(modelResolver.resolveCallPool(snapshot.model().connectionRef()))
                    .isEqualTo(secondId);
            assertThat(modelResolver.resolveCallPool(
                            new ConnectionRef(UsageFundingSource.INSTANCE, Long.MAX_VALUE, modelId, workspace.getId())))
                    .isNull();
        });
    }

    @Test
    void shouldShareOneRateAccountAcrossDirectScriptsAndComposition() throws Exception {
        try (MockWebServer upstream = new MockWebServer()) {
            upstream.start();
            for (int i = 0; i < 3; i++)
                upstream.enqueue(json("{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}"));
            ReviewJob job = practiceScriptJob(upstream.url("/v1").toString());
            job.setLeasedUntil(Instant.now().plusSeconds(60));
            job = jobRepository.saveAndFlush(job);
            var snapshot = ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper);
            var pool = new LlmCallPool();
            pool.setSlug("scripts-" + UUID.randomUUID());
            pool.setDisplayName("Shared script and composition capacity");
            pool.setMaxConcurrentCalls(4);
            pool.setRequestsPerMinute(3);
            pool.setTokensPerMinute(300L);
            pool.setTokensPerCall(100L);
            pool = callPools.saveAndFlush(pool);
            long poolId = pool.getId();
            for (var model : List.of(
                    snapshot.model(), Objects.requireNonNull(snapshot.practiceScriptSlot(ModelKind.EMBEDDING)))) {
                var catalog = modelRepository
                        .findById(Objects.requireNonNull(
                                Objects.requireNonNull(model).modelId()))
                        .orElseThrow();
                catalog.setCallPoolId(poolId);
                modelRepository.saveAndFlush(catalog);
            }
            var capacity = new LlmCallPermits(
                    callPools,
                    callRows,
                    transactionTemplate,
                    new LlmCallPoolProperties(Duration.ofMinutes(1), Duration.ofMinutes(5)),
                    "worker-1",
                    meterRegistry,
                    modelResolver);
            var service = proxyService(200_000, 1000, WebClient.create(), capacity);
            assertThat(callPracticeScript(
                                    service,
                                    job,
                                    "/internal/llm/practice-script/chat/chat/completions",
                                    PRACTICE,
                                    "{\"messages\":[]}")
                            .status())
                    .isEqualTo(200);
            assertThat(callPracticeScript(
                                    service,
                                    job,
                                    "/internal/llm/practice-script/embedding/embeddings",
                                    PRACTICE,
                                    "{\"input\":[\"a\"]}")
                            .status())
                    .isEqualTo(200);
            job.setAdmissionAttempt(job.getAttempt());
            job.setAdmissionDigest("admitted");
            job = jobRepository.saveAndFlush(job);
            var routes =
                    new ProxyRoutingService(jobRepository, turnRepository, objectMapper, attemptUsage, reviewSnapshots);
            var route = routes.workerRouting(
                    job.getId(),
                    workspace.getId(),
                    job.getAttempt(),
                    "worker-1",
                    Instant.now().plusSeconds(5));
            byte[] request = "{\"messages\":[]}".getBytes(StandardCharsets.UTF_8);
            var composed = Objects.requireNonNull(
                    service.serve(route, LlmApiProtocol.OPENAI_COMPLETIONS, null, null, new HttpHeaders(), request));
            assertThat(composed.getStatusCode().value()).isEqualTo(200);
            var blockedRoute = routes.workerRouting(
                    job.getId(),
                    workspace.getId(),
                    job.getAttempt(),
                    "worker-1",
                    Instant.now().plusMillis(100));
            var blocked = Objects.requireNonNull(service.serve(
                    blockedRoute, LlmApiProtocol.OPENAI_COMPLETIONS, null, null, new HttpHeaders(), request));
            assertThat(blocked.getStatusCode().value()).isEqualTo(503);
            assertThat(blocked.getHeaders().getFirst(LlmProxyService.REFUSAL_HEADER))
                    .isEqualTo("CAPACITY");
            assertThat(upstream.getRequestCount()).isEqualTo(3);
            assertThat(callRows.rates(poolId).getRequests()).isEqualTo(3);
            assertThat(callRows.rates(poolId).getTokens()).isEqualTo(300);
            assertThat(callRows.countActive(poolId)).isZero();
        }
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "CHAT|/internal/llm/practice-script/chat/chat/completions|/v1/chat/completions|"
                        + "{\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":4}}|11|4",
                "DECISION|/internal/llm/practice-script/decision/decisions|/v1/decisions|"
                        + "{\"answers\":[],\"usage\":{\"input_tokens\":40,\"output_tokens\":3}}|40|3",
                "EMBEDDING|/internal/llm/practice-script/embedding/embeddings|/v1/embeddings|"
                        + "{\"data\":[],\"usage\":{\"prompt_tokens\":12,\"total_tokens\":12}}|12|0",
                "RERANKING|/internal/llm/practice-script/reranking/rerank|/v1/rerank|{\"results\":[]}|0|0"
            })
    void shouldForwardEachPracticeScriptOpWithTheCatalogModelWhenTheJobTokenCallsIt(
            ModelKind slot, String path, String upstreamPath, String reply, long input, long output) throws Exception {
        try (MockWebServer upstream = new MockWebServer()) {
            upstream.start();
            upstream.enqueue(json(reply));
            ReviewJob job = practiceScriptJob(upstream.url("/v1").toString());

            ProxyResult result = callPracticeScript(job, path, "{\"model\":\"runner-controlled\",\"input\":[\"a\"]}");

            assertThat(result.status()).isEqualTo(200);
            RecordedRequest sent = Objects.requireNonNull(upstream.takeRequest(5, TimeUnit.SECONDS));
            assertThat(sent.getUrl().encodedPath()).isEqualTo(upstreamPath);
            assertThat(sent.getHeaders().get("Authorization")).isEqualTo("Bearer upstream-secret");
            assertThat(objectMapper
                            .readTree(Objects.requireNonNull(sent.getBody()).utf8())
                            .path("model")
                            .asString())
                    .isEqualTo(slot.name().toLowerCase(Locale.ROOT) + "-upstream");
            assertThat(practiceScriptRows(job, 0)).containsExactly(new UsageRow(slot, PRACTICE, 1, input, output));
            assertThat(jdbc.queryForObject(
                            "SELECT data_handling_tier FROM review_job_script_usage WHERE job_id = ?",
                            String.class,
                            job.getId()))
                    .isEqualTo(slot == ModelKind.CHAT ? "UNDECLARED" : "CLOUD");
            ReviewJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
            // The review's ledger row bills the chat model, so only chat calls reach the job's counters.
            assertThat(Objects.requireNonNullElse(reloaded.getLlmCalls(), 0)).isEqualTo(slot == ModelKind.CHAT ? 1 : 0);
        }
    }

    @Test
    void shouldServeAWorkerCallOnlyForTheRunningAttemptOnThisWorkerAndBillIt() throws Exception {
        try (MockWebServer upstream = new MockWebServer()) {
            upstream.start();
            upstream.enqueue(json("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],"
                    + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}"));
            ReviewJob job = practiceScriptJob(upstream.url("/v1").toString());
            var gateway = new ReviewModelGateway(
                    new ProxyRoutingService(jobRepository, turnRepository, objectMapper, attemptUsage, reviewSnapshots),
                    proxyService(200_000),
                    objectMapper);
            byte[] body = "{\"model\":\"any\",\"messages\":[{\"role\":\"user\",\"content\":\"Compose.\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            Instant deadline = Instant.now().plusSeconds(60);

            var served = gateway.call(
                    new ReviewModelGateway.Attempt(job.getId(), workspace.getId(), 0, "worker-1", deadline), body);
            var staleAttempt = gateway.call(
                    new ReviewModelGateway.Attempt(job.getId(), workspace.getId(), 1, "worker-1", deadline), body);
            var otherWorker = gateway.call(
                    new ReviewModelGateway.Attempt(job.getId(), workspace.getId(), 0, "worker-2", deadline), body);
            var late = gateway.call(
                    new ReviewModelGateway.Attempt(
                            job.getId(),
                            workspace.getId(),
                            0,
                            "worker-1",
                            Instant.now().minusSeconds(1)),
                    body);

            assertThat(served.owned()).isTrue();
            assertThat(served.status()).isEqualTo(200);
            assertThat(served.refusal()).isNull();
            RecordedRequest sent = Objects.requireNonNull(upstream.takeRequest(5, TimeUnit.SECONDS));
            assertThat(sent.getUrl().encodedPath()).isEqualTo("/v1/chat/completions");
            assertThat(objectMapper
                            .readTree(Objects.requireNonNull(sent.getBody()).utf8())
                            .path("model")
                            .asString())
                    .isEqualTo("chat-upstream");
            assertThat(staleAttempt.owned()).isFalse();
            assertThat(otherWorker.owned()).isFalse();
            assertThat(late.status()).isEqualTo(403);
            // The proxy names its own rule, so the worker need not read the refusal's words.
            assertThat(late.refusal()).isEqualTo(ReviewModelGateway.Refusal.WORK_TIME);
            assertThat(upstream.getRequestCount()).isEqualTo(1);
            // The call bills the attempt that made it, as the attempt's sandbox calls do.
            assertThat(jobRepository.findById(job.getId()).orElseThrow().getLlmCalls())
                    .isEqualTo(1);
        }
    }

    @Test
    void shouldKeepTheResultUploadButMakeNoModelCallOnceTheWorkTimeHasEnded() throws Exception {
        try (MockWebServer upstream = new MockWebServer()) {
            upstream.start();
            ReviewJob job = practiceScriptJob(upstream.url("/v1").toString());
            Instant work = Instant.now().plusSeconds(1);
            String token = jwtIssuer.issueForAttempt(
                    AttemptJwt.Kind.REVIEW,
                    job.getId(),
                    workspace.getId(),
                    job.getAttempt(),
                    work,
                    work.plus(Duration.ofMinutes(10)));
            // Real time passes the signed work deadline; the token itself stays valid for the upload.
            while (!Instant.now().isAfter(work)) Thread.sleep(50);

            assertThat(authenticate("POST", "/internal/llm/runtime/" + job.getId() + "/result", token)
                            .status())
                    .isEqualTo(200);
            ProxyResult refused = callPracticeScript(
                    proxyService(200_000), token, "/internal/llm/practice-script/embedding/embeddings", PRACTICE, "{}");

            assertThat(refused.status()).isEqualTo(403);
            assertThat(refused.body()).isEqualTo(LlmProxyService.WORK_TIME_OVER);
            assertThat(upstream.getRequestCount()).isZero();
            assertThat(practiceScriptRows(job, 0)).isEmpty();
        }
    }

    @Test
    void shouldRefuseWithPaymentRequiredWhenTheAttemptsDirectModelTokensReachTheCap() throws Exception {
        try (MockWebServer upstream = new MockWebServer()) {
            upstream.start();
            upstream.enqueue(json("{\"data\":[],\"usage\":{\"prompt_tokens\":120}}"));
            ReviewJob job = practiceScriptJob(upstream.url("/v1").toString());
            LlmProxyService capped = proxyService(100);

            ProxyResult first = callPracticeScript(
                    capped, job, "/internal/llm/practice-script/embedding/embeddings", PRACTICE, "{}");
            ProxyResult second =
                    callPracticeScript(capped, job, "/internal/llm/practice-script/decision/decisions", PRACTICE, "{}");

            assertThat(first.status()).isEqualTo(200);
            assertThat(second.status()).isEqualTo(402);
            assertThat(second.body()).isEqualTo("Practice script budget spent");
            assertThat(upstream.getRequestCount()).isEqualTo(1);
        }
    }

    @Test
    void shouldPersistUsageCompletenessWhenReportedAndUnknownCallsShareAnAggregate() throws Exception {
        try (MockWebServer upstream = new MockWebServer()) {
            upstream.start();
            upstream.enqueue(json("{\"data\":[],\"usage\":{\"prompt_tokens\":10}}"));
            upstream.enqueue(json("{\"data\":[]}"));
            ReviewJob job = practiceScriptJob(upstream.url("/v1").toString());
            String path = "/internal/llm/practice-script/embedding/embeddings";

            assertThat(callPracticeScript(job, path, "{}").status()).isEqualTo(200);
            assertThat(callPracticeScript(job, path, "{}").status()).isEqualTo(200);

            var totals = jobRepository.sumPracticeScriptUsageByKind(workspace.getId(), job.getId(), 0);
            assertThat(totals).containsExactly(new PracticeScriptKindTotal(ModelKind.EMBEDDING, 2, 10, 0, 0, 0, 0, 1));
            assertThat(totals.getFirst().hasCompleteUsage()).isFalse();
        }
    }

    @Test
    void shouldPersistReportedZeroWhenTheProviderReportsZeroUsage() throws Exception {
        try (MockWebServer upstream = new MockWebServer()) {
            upstream.start();
            upstream.enqueue(json("{\"data\":[],\"usage\":{\"prompt_tokens\":0}}"));
            ReviewJob job = practiceScriptJob(upstream.url("/v1").toString());

            assertThat(callPracticeScript(job, "/internal/llm/practice-script/embedding/embeddings", "{}")
                            .status())
                    .isEqualTo(200);

            var totals = jobRepository.sumPracticeScriptUsageByKind(workspace.getId(), job.getId(), 0);
            assertThat(totals).containsExactly(new PracticeScriptKindTotal(ModelKind.EMBEDDING, 1, 0, 0, 0, 0, 0, 1));
            assertThat(totals.getFirst().hasCompleteUsage()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldKeepTheTerminalLedgerUnpricedWhenAReportedCallIsFollowedByATimeout(boolean beforeHeaders)
            throws Exception {
        try (MockWebServer upstream = new MockWebServer()) {
            upstream.start();
            upstream.enqueue(json("{\"data\":[],\"usage\":{\"prompt_tokens\":10}}"));
            var delayed = new MockResponse.Builder().code(200).body("{\"data\":[]}");
            if (beforeHeaders) delayed.headersDelay(3, TimeUnit.SECONDS);
            else delayed.bodyDelay(3, TimeUnit.SECONDS);
            upstream.enqueue(delayed.build());
            ReviewJob job = practiceScriptJob(upstream.url("/v1").toString());
            WebClient client = WebClient.builder()
                    .clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                            .responseTimeout(Duration.ofSeconds(1))
                            .disableRetry(true)))
                    .build();
            LlmProxyService service = proxyService(200_000, 1000, client);
            String path = "/internal/llm/practice-script/embedding/embeddings";

            assertThat(callPracticeScript(service, job, path, PRACTICE, "{}").status())
                    .isEqualTo(200);
            assertThat(callPracticeScript(service, job, path, PRACTICE, "{}").status())
                    .isEqualTo(502);
            assertThat(upstream.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(upstream.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(upstream.getRequestCount()).isEqualTo(2);
            assertThat(jobRepository.sumPracticeScriptUsageByKind(workspace.getId(), job.getId(), 0))
                    .containsExactly(new PracticeScriptKindTotal(ModelKind.EMBEDDING, 2, 10, 0, 0, 0, 0, 1));

            transactionTemplate.executeWithoutResult(tx -> {
                ReviewJob ending = jobRepository.findById(job.getId()).orElseThrow();
                ending.setStatus(ReviewJobStatus.COMPLETED);
                jobRepository.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, ending));
                TerminalUsageTestFixtures.appendProxyUsage(jobRepository, usageRecorder, ending, objectMapper);
            });

            var stored = jdbc.queryForMap(
                    "SELECT total_calls, input_tokens, unreported_calls, pricing_state, cost_usd "
                            + "FROM llm_usage_event WHERE source_id = ? AND source_type = 'PRACTICE_SCRIPT_EMBEDDING'",
                    job.getId());
            assertThat(stored.get("total_calls"))
                    .isInstanceOfSatisfying(
                            Number.class, value -> assertThat(value.intValue()).isEqualTo(2));
            assertThat(stored.get("input_tokens"))
                    .isInstanceOfSatisfying(
                            Number.class, value -> assertThat(value.longValue()).isEqualTo(10));
            assertThat(stored.get("unreported_calls"))
                    .isInstanceOfSatisfying(
                            Number.class, value -> assertThat(value.longValue()).isEqualTo(1));
            assertThat(stored.get("pricing_state")).isEqualTo("UNPRICED");
            assertThat(stored.get("cost_usd")).isNull();
        }
    }

    @Test
    void shouldCountEachPracticeApartAndCapThemTogetherWhenTwoPracticesCallOneModel() throws Exception {
        try (MockWebServer upstream = new MockWebServer()) {
            upstream.start();
            for (int call = 0; call < 3; call++) {
                upstream.enqueue(json("{\"data\":[],\"usage\":{\"prompt_tokens\":60}}"));
            }
            ReviewJob job = practiceScriptJob(upstream.url("/v1").toString());
            LlmProxyService capped = proxyService(100);
            String path = "/internal/llm/practice-script/embedding/embeddings";

            ProxyResult first = callPracticeScript(capped, job, path, PRACTICE, "{}");
            ProxyResult second = callPracticeScript(capped, job, path, "test-coverage", "{}");
            ProxyResult third = callPracticeScript(capped, job, path, "test-coverage", "{}");

            assertThat(first.status()).isEqualTo(200);
            assertThat(second.status()).isEqualTo(200);
            assertThat(third.status()).isEqualTo(402);
            assertThat(practiceScriptRows(job, 0))
                    .containsExactlyInAnyOrder(
                            new UsageRow(ModelKind.EMBEDDING, PRACTICE, 1, 60, 0),
                            new UsageRow(ModelKind.EMBEDDING, "test-coverage", 1, 60, 0));
            assertThat(jobRepository.sumPracticeScriptUsageByKind(workspace.getId(), job.getId(), 0))
                    .containsExactly(new PracticeScriptKindTotal(ModelKind.EMBEDDING, 2, 120, 0, 0, 0, 0, 2));
        }
    }

    @Test
    void shouldCountTheSearchUnitsAndRefuseAtTheirLimitWhenCohereBillsReranksInThem() throws Exception {
        try (MockWebServer upstream = new MockWebServer()) {
            upstream.start();
            for (int call = 0; call < 2; call++) {
                upstream.enqueue(json("{\"id\":\"r\",\"results\":[{\"index\":0,\"relevance_score\":0.5}],"
                        + "\"meta\":{\"billed_units\":{\"search_units\":3}}}"));
            }
            ReviewJob job = practiceScriptJob(upstream.url("/v1").toString());
            LlmProxyService capped = proxyService(200_000, 6);
            String path = "/internal/llm/practice-script/reranking/rerank";

            ProxyResult first =
                    callPracticeScript(capped, job, path, PRACTICE, "{\"query\":\"q\",\"documents\":[\"a\"]}");
            ProxyResult second =
                    callPracticeScript(capped, job, path, PRACTICE, "{\"query\":\"q\",\"documents\":[\"a\"]}");
            ProxyResult third =
                    callPracticeScript(capped, job, path, PRACTICE, "{\"query\":\"q\",\"documents\":[\"a\"]}");

            assertThat(first.status()).isEqualTo(200);
            assertThat(second.status()).isEqualTo(200);
            assertThat(third.status()).isEqualTo(402);
            assertThat(third.body()).isEqualTo("Practice script budget spent");
            assertThat(upstream.getRequestCount()).isEqualTo(2);
            assertThat(jdbc.queryForMap(
                            "SELECT calls, input_tokens, search_units FROM review_job_script_usage "
                                    + "WHERE job_id = ? AND model_kind = 'RERANKING'",
                            job.getId()))
                    .containsEntry("calls", 2)
                    .containsEntry("input_tokens", 0L)
                    .containsEntry("search_units", 6L);
        }
    }

    @Test
    void shouldKeepTheCachedShareApartWhenAPracticeScriptChatCallReadsTheCache() throws Exception {
        try (MockWebServer upstream = new MockWebServer()) {
            upstream.start();
            upstream.enqueue(json("{\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":5,"
                    + "\"prompt_tokens_details\":{\"cached_tokens\":60}}}"));
            ReviewJob job = practiceScriptJob(upstream.url("/v1").toString());

            ProxyResult result =
                    callPracticeScript(job, "/internal/llm/practice-script/chat/chat/completions", "{\"messages\":[]}");

            assertThat(result.status()).isEqualTo(200);
            assertThat(jdbc.queryForMap(
                            "SELECT input_tokens, cache_read_tokens, cache_write_tokens, output_tokens "
                                    + "FROM review_job_script_usage WHERE job_id = ? AND model_kind = 'CHAT'",
                            job.getId()))
                    .containsEntry("input_tokens", 40L)
                    .containsEntry("cache_read_tokens", 60L)
                    .containsEntry("cache_write_tokens", 0L)
                    .containsEntry("output_tokens", 5L);
            ReviewJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
            assertThat(reloaded.getLlmInputTokens()).isEqualTo(40);
            assertThat(reloaded.getLlmCacheReadTokens()).isEqualTo(60);
            assertThat(jobRepository.sumPracticeScriptUsageByKind(workspace.getId(), job.getId(), 0))
                    .singleElement()
                    .extracting(PracticeScriptKindTotal::tokens)
                    .isEqualTo(105L);
        }
    }

    @Test
    void shouldSettleALateCallOnItsOwnAttemptRowWhenTheJobIsRequeued() {
        ReviewJob job = practiceScriptJob("https://models.example.com/v1");
        ProxyUsageAccumulator accumulator = accumulator();
        register(job, ModelKind.DECISION);
        var attemptZero = billed(job, 0, UsageSourceType.PRACTICE_SCRIPT_DECISION);
        var decision = new PracticeScriptCall(ModelKind.DECISION, null, PRACTICE);
        accumulator.accumulate(attemptZero, decision, new ProxyTokenUsage(10, 2, 0, 0, 0));
        accumulator.accumulate(attemptZero, decision, new ProxyTokenUsage(5, 1, 0, 0, 0));

        job.setAttempt(1);
        job = jobRepository.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
        register(job, ModelKind.DECISION);
        accumulator.accumulate(attemptZero, decision, new ProxyTokenUsage(1000, 0, 0, 0, 0));
        accumulator.accumulate(
                billed(job, 1, UsageSourceType.PRACTICE_SCRIPT_DECISION), decision, new ProxyTokenUsage(7, 0, 0, 0, 0));

        // The late call belongs to attempt 0, which authenticated it; attempt 1 keeps only its own call.
        assertThat(practiceScriptRows(job, 0)).containsExactly(new UsageRow(ModelKind.DECISION, PRACTICE, 3, 1015, 3));
        assertThat(practiceScriptRows(job, 1)).containsExactly(new UsageRow(ModelKind.DECISION, PRACTICE, 1, 7, 0));
    }

    @Test
    void shouldDropAPracticeScriptCallWhenItsAttemptWasNeverRegistered() {
        ReviewJob job = practiceScriptJob("https://models.example.com/v1");
        job.setStatus(ReviewJobStatus.COMPLETED);
        job = jobRepository.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));

        accumulator()
                .accumulate(
                        billed(job, 0, UsageSourceType.PRACTICE_SCRIPT_EMBEDDING),
                        new PracticeScriptCall(ModelKind.EMBEDDING, null, PRACTICE),
                        new ProxyTokenUsage(9, 0, 0, 0, 0));

        assertThat(practiceScriptRows(job, 0)).isEmpty();
    }

    private ProxyResult callPracticeScript(ReviewJob job, String path, String body) throws Exception {
        return callPracticeScript(proxyService(200_000), job, path, PRACTICE, body);
    }

    private ProxyResult callPracticeScript(
            LlmProxyService service, ReviewJob job, String path, String practice, String body) throws Exception {
        return callPracticeScript(service, tokenFor(job), path, practice, body);
    }

    private ProxyResult callPracticeScript(
            LlmProxyService service, String token, String path, String practice, String body) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Authorization", "Bearer " + token);
        HttpHeaders headers = new HttpHeaders();
        headers.set(JobTokenAuthenticationFilter.PRACTICE_SCRIPT_PRACTICE_HEADER, practice);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<@Nullable ResponseEntity<?>> served = new AtomicReference<>();
        filter.doFilter(
                request,
                response,
                (filteredRequest, filteredResponse) -> served.set(service.proxyPracticeScript(
                        request, response, headers, body.getBytes(StandardCharsets.UTF_8))));
        ResponseEntity<?> result = served.get();
        if (result == null) return new ProxyResult(response.getStatus(), null);
        Object payload = result.getBody();
        return new ProxyResult(
                result.getStatusCode().value(),
                payload instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(payload));
    }

    private record ProxyResult(int status, @Nullable String body) {}

    // The worker role is off here; supply the accumulator's production transaction boundary explicitly.
    private LlmProxyService proxyService(long maxTokensPerAttempt) {
        return proxyService(maxTokensPerAttempt, 1000);
    }

    private LlmProxyService proxyService(long maxTokensPerAttempt, long maxSearchUnitsPerAttempt) {
        return proxyService(maxTokensPerAttempt, maxSearchUnitsPerAttempt, WebClient.create());
    }

    private LlmProxyService proxyService(long maxTokensPerAttempt, long maxSearchUnitsPerAttempt, WebClient client) {
        return proxyService(maxTokensPerAttempt, maxSearchUnitsPerAttempt, client, mock(LlmCallPermits.class));
    }

    private LlmProxyService proxyService(
            long maxTokensPerAttempt, long maxSearchUnitsPerAttempt, WebClient client, LlmCallPermits capacity) {
        ProxyRequestPolicy policy = mock(ProxyRequestPolicy.class);
        when(policy.allows(any())).thenReturn(true);
        return new LlmProxyService(
                Tracer.NOOP,
                client,
                modelResolver,
                objectMapper,
                new ProxyAccounting(
                        new ProxyBudgetGate(
                                mock(LlmBudgetService.class),
                                new PracticeReviewProperties(
                                        false,
                                        15,
                                        5,
                                        null,
                                        12,
                                        16000,
                                        3,
                                        maxTokensPerAttempt,
                                        maxSearchUnitsPerAttempt,
                                        Duration.ofMinutes(10))),
                        accumulator(),
                        mock(MentorTurnUsageAccumulator.class),
                        meterRegistry,
                        objectMapper),
                policy,
                mock(ScanCallGuard.class),
                new ProxyRoutingService(jobRepository, turnRepository, objectMapper, attemptUsage, reviewSnapshots),
                new ProxyCallsInFlight(3),
                new LlmConnectionHealth(CircuitBreakerRegistry.ofDefaults()),
                capacity,
                new HephCallProperties(Duration.ofMinutes(5), Duration.ofMinutes(2)));
    }

    // The worker role is off here, so the accumulator is built with its own transaction boundary.
    private ProxyUsageAccumulator accumulator() {
        return new ProxyUsageAccumulator(attemptUsage, transactionManager, new SimpleMeterRegistry());
    }

    private static ProxyRouting.BilledAttempt billed(ReviewJob job, int attempt, UsageSourceType sourceType) {
        return new ProxyRouting.BilledAttempt(
                sourceType, job.getId(), attempt, BigDecimal.ZERO, "worker-1", 0L, 0L, job.getWorkspaceId());
    }

    private void register(ReviewJob job, ModelKind kind) {
        assertThat(attemptUsage.registerCall(
                        job.getId(),
                        job.getWorkspaceId(),
                        job.getAttempt(),
                        job.getWorkerId(),
                        kind,
                        FrozenCharge.UNKNOWN))
                .isTrue();
    }

    private List<UsageRow> practiceScriptRows(ReviewJob job, int attempt) {
        return jdbc.query(
                "SELECT u.model_kind, p.slug AS practice_slug, u.calls, u.input_tokens, u.output_tokens FROM"
                        + " review_job_script_usage u JOIN practice p ON"
                        + " (p.id,p.workspace_id)=(u.practice_id,u.workspace_id) WHERE u.job_id = ? AND u.attempt = ?",
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

    private ReviewJob practiceScriptJob(String baseUrl) {
        Map<ModelKind, CatalogModel> catalog = new EnumMap<>(ModelKind.class);
        for (ModelKind kind : ModelKind.values()) {
            LlmApiProtocol protocol = switch (kind) {
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
                UsageFundingSource.INSTANCE,
                UsagePricingState.PRICED,
                null,
                null,
                BigDecimal.ONE,
                BigDecimal.ONE,
                null,
                null);
        Map<ModelKind, FrozenModel> slots = new EnumMap<>(ModelKind.class);
        for (ModelKind kind : new ModelKind[] {ModelKind.DECISION, ModelKind.EMBEDDING, ModelKind.RERANKING}) {
            CatalogModel model = Objects.requireNonNull(catalog.get(kind));
            slots.put(
                    kind,
                    new FrozenModel(
                            LlmApiProtocol.parse(model.protocol()).orElseThrow(),
                            baseUrl,
                            kind.name().toLowerCase(Locale.ROOT) + "-upstream",
                            UsageFundingSource.INSTANCE,
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
                        new FrozenModel(
                                LlmApiProtocol.parse(chat.protocol()).orElseThrow(),
                                baseUrl,
                                "chat-upstream",
                                UsageFundingSource.INSTANCE,
                                chat.connectionId(),
                                chat.modelId(),
                                workspace.getId(),
                                DataHandlingTier.UNDECLARED,
                                null,
                                price),
                        null,
                        null,
                        600,
                        false,
                        null)
                .withPracticeScript(slots);

        ReviewJob job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());

        job.setStatus(ReviewJobStatus.RUNNING);
        job.setWorkerId("worker-1");
        job.setConfigSnapshot(snapshot.toJson(objectMapper));
        return jobRepository.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
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
        AtomicReference<@Nullable Authentication> authentication = new AtomicReference<>();
        filter.doFilter(
                request,
                response,
                (filteredRequest, filteredResponse) -> authentication.set(Objects.requireNonNull(
                        SecurityContextHolder.getContext().getAuthentication())));
        return new AuthenticationResult(response.getStatus(), authentication.get());
    }

    private record AuthenticationResult(
            int status, @Nullable Authentication authentication) {}

    private String tokenFor(ReviewJob job) {
        Instant work = Instant.now().plus(Duration.ofMinutes(5));
        return jwtIssuer.issueForAttempt(
                AttemptJwt.Kind.REVIEW,
                job.getId(),
                workspace.getId(),
                job.getAttempt(),
                work,
                work.plus(Duration.ofMinutes(10)));
    }

    private ProxyRouting routingOf(AuthenticationResult result) {
        Authentication authentication = Objects.requireNonNull(result.authentication(), "token did not authenticate");
        return new ProxyRoutingService(jobRepository, turnRepository, objectMapper, attemptUsage, reviewSnapshots)
                .resolve(
                        (ProxyIdentity) Objects.requireNonNull(authentication.getPrincipal()),
                        new MockHttpServletRequest("POST", "/internal/llm/chat/completions"));
    }

    private ReviewJob runningJob(boolean modelEnabled) {
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

        var snapshot = new ConfigSnapshot(
                        ConfigSnapshot.SCHEMA_VERSION,
                        new FrozenModel(
                                LlmApiProtocol.OPENAI_COMPLETIONS,
                                connection.getBaseUrl(),
                                model.getUpstreamModelId(),
                                UsageFundingSource.INSTANCE,
                                connection.getId(),
                                model.getId(),
                                workspace.getId(),
                                DataHandlingTier.UNDECLARED,
                                null,
                                null),
                        null,
                        null,
                        600,
                        false,
                        null)
                .toJson(objectMapper);

        ReviewJob job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());

        job.setStatus(ReviewJobStatus.RUNNING);
        job.setWorkerId("worker-1");
        job.setConfigSnapshot(snapshot);
        return jobRepository.save(RuntimeJobTestData.persistParent(jdbc, job));
    }
}
