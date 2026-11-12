package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptKeyFixtures;
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
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPrice;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPriceRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver.ConnectionRef;
import de.tum.cit.aet.hephaestus.agent.catalog.ModelVisibility;
import de.tum.cit.aet.hephaestus.agent.catalog.PricingMode;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.productfeedback.DraftReportAdapterFixture;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetHeadroom;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionRequest;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionTestSupport;
import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDraftCalls;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDrafts;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import mockwebserver3.Dispatcher;
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
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.ObjectMapper;

class LlmProxyIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String PRACTICE = "comment-quality";

    /** The output limit that the fixture jobs freeze for their chat model. */
    private static final int CHAT_OUTPUT_LIMIT = 4096;

    @Autowired
    private ReviewSnapshotQuery reviewSnapshots;

    @Autowired
    private ReviewJobRepository jobRepository;

    @Autowired
    private AgentRuns agentRuns;

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

    private final AttemptTokenVerifier jwtVerifier =
            new AttemptTokenVerifier(AttemptKeyFixtures.keyring(KEY), new SimpleMeterRegistry());

    private final AttemptTokenIssuer jwtIssuer = new AttemptTokenIssuer(AttemptKeyFixtures.keyring(KEY));

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LlmUsageRecorder usageRecorder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private LlmCallPoolRepository callPools;

    @Autowired
    private LlmCallPermitRepository callRows;

    @Autowired
    private ProductFeedbackDrafts drafts;

    @Autowired
    private ProductFeedbackDraftCalls draftCalls;

    @Autowired
    private LlmAdmissionService modelAdmission;

    @Autowired
    private LlmModelPriceRepository modelPrices;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AgentActions actions;

    @Autowired
    private ActionExecutor actionExecutor;

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
                jobRepository, agentRuns, jwtVerifier, null, Map.of(), Mockito.mock(ProxyAccounting.class));
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

    @ParameterizedTest
    @CsvSource({"200,100", "429,100", "200,0"})
    void shouldDraftThroughTheRealInstanceResolverAndLedgerAndSettleTheAction(int providerStatus, int dailyLimit)
            throws Exception {
        try (var upstream = new MockWebServer()) {
            upstream.start();
            upstream.enqueue(new MockResponse.Builder()
                    .code(providerStatus)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Retry-After", "5")
                    .body(
                            providerStatus == 200
                                    ? "{\"choices\":[{\"message\":{\"content\":\"{}\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}"
                                    : "{\"error\":{\"message\":\"rate limit\"}}")
                    .build());
            var connection = LlmCatalogTestFixtures.connection("draft-live-" + UUID.randomUUID());
            connection.setBaseUrl(upstream.url("/v1").toString());
            connection = connectionRepository.saveAndFlush(connection);
            var model = modelRepository.saveAndFlush(LlmCatalogTestFixtures.model(
                    connection, "draft-live-" + UUID.randomUUID(), "draft-live", ModelVisibility.GRANTED, true));
            model.setMaxOutputTokens(100);
            modelRepository.saveAndFlush(model);
            var price = new LlmModelPrice();
            price.setModel(model);
            price.setPricingMode(PricingMode.PRICED);
            price.setPer1mInputUsd(new BigDecimal("2"));
            price.setPer1mOutputUsd(new BigDecimal("8"));
            price.setEffectiveFrom(Instant.now());
            modelPrices.saveAndFlush(price);
            var account = new Account();
            account.setDisplayName("Draft reporter");
            account = accounts.saveAndFlush(account);
            UUID report = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO product_feedback(id,account_id,kind,message,submission_minute,created_at)
                VALUES (?,?,'BUG','Synthetic report',clock_timestamp(),clock_timestamp())
                """, report, account.getId());
            jdbc.update("""
                INSERT INTO product_feedback_filing(feedback_id,state,attempt,allow_public,risk_latch,input_tokens,output_tokens,file_disclosed,prompt_input,
                    created_at,updated_at,next_attempt_at)
                VALUES (?,'DRAFT_QUEUED',1,false,false,0,0,true,'Synthetic report',clock_timestamp(),clock_timestamp(),clock_timestamp())
                """, report);
            var oldDestination = jdbc.queryForList(
                    "SELECT llm_model_id,daily_draft_limit FROM product_feedback_destination WHERE id=1");
            jdbc.update("""
                INSERT INTO product_feedback_destination(id,llm_model_id,daily_draft_limit,paused,notice_version,updated_at)
                VALUES (1,?,?,false,1,clock_timestamp()) ON CONFLICT(id) DO UPDATE
                    SET llm_model_id=excluded.llm_model_id,daily_draft_limit=excluded.daily_draft_limit
                """, model.getId(), dailyLimit);
            try {
                String lane = "file_issue:" + report;
                var request = new ActionRequest(
                        ActionKind.DRAFT_REPORT,
                        null,
                        lane,
                        "draft:" + report + ":1",
                        objectMapper.valueToTree(new ProductFeedbackDrafts.Payload(report, 1)),
                        account.getId(),
                        3,
                        Instant.now().plusSeconds(600));
                UUID action = Objects.requireNonNull(transactionTemplate.execute(tx -> actions.enqueue(request)));
                var proxy = proxyService(WebClient.builder().build(), mock(LlmBudgetService.class), admittingPermits());
                var adapter = DraftReportAdapterFixture.adapter(
                        drafts,
                        draftCalls,
                        modelResolver,
                        new ProductFeedbackModelGateway(proxy, objectMapper),
                        objectMapper,
                        usageRecorder);
                ActionTestSupport.due(jdbc, lane);
                var result = ActionTestSupport.runLane(actionExecutor, jdbc, lane, adapter)
                        .orElseThrow();
                if (dailyLimit == 0) {
                    assertThat(result).isInstanceOf(ActionResult.Failed.class);
                    assertThat(upstream.getRequestCount()).isZero();
                    assertThat(meterRegistry
                                    .find(AgentMetrics.LLM_BUDGET_BLOCKED)
                                    .counter())
                            .isNull();
                    assertThat(jdbc.queryForObject(
                                    "SELECT count(*) FROM llm_usage_event WHERE action_id=?", Long.class, action))
                            .isZero();
                    return;
                }
                assertThat(upstream.getRequestCount()).isEqualTo(1);
                var sent = Objects.requireNonNull(upstream.takeRequest(5, TimeUnit.SECONDS));
                var body = objectMapper.readTree(
                        Objects.requireNonNull(sent.getBody()).utf8());
                long output = body.has("max_completion_tokens")
                        ? body.path("max_completion_tokens").asLong()
                        : body.path("max_tokens").asLong();
                assertThat(output).isEqualTo(100);
                if (providerStatus == 200) {
                    assertThat(result).isInstanceOf(ActionResult.Done.class);
                    assertThat(jdbc.queryForObject("SELECT state FROM agent_action WHERE id=?", String.class, action))
                            .isEqualTo("DONE");
                    assertThat(jdbc.queryForObject(
                                    "SELECT state FROM product_feedback_filing WHERE feedback_id=?",
                                    String.class,
                                    report))
                            .isEqualTo("ANSWERED");
                } else {
                    assertThat(result).isInstanceOf(ActionResult.Retry.class);
                    assertThat(jdbc.queryForObject(
                                    "SELECT attempts FROM agent_action WHERE id=?", Integer.class, action))
                            .isEqualTo(1);
                }
                var row = jdbc.queryForMap("SELECT * FROM llm_usage_event WHERE action_id=?", action);
                assertThat(row)
                        .containsEntry("workspace_id", null)
                        .containsEntry("run_id", null)
                        .containsEntry("call_state", "ENDED");
                if (providerStatus == 200) {
                    assertThat(row).containsEntry("input_tokens", 10L).containsEntry("output_tokens", 2L);
                    assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0.000036");
                }
            } finally {
                if (oldDestination.isEmpty()) jdbc.update("DELETE FROM product_feedback_destination WHERE id=1");
                else
                    jdbc.update(
                            "UPDATE product_feedback_destination SET llm_model_id=?,daily_draft_limit=? WHERE id=1",
                            oldDestination.getFirst().get("llm_model_id"),
                            oldDestination.getFirst().get("daily_draft_limit"));
            }
        }
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

        AuthenticationResult result =
                authenticate(new AttemptTokenIssuer(AttemptKeyFixtures.keyring(AttemptSigningKey.generate()))
                        .issueForReview(
                                job.getId(),
                                job.getWorkspaceId(),
                                ReviewRunKind.epoch(job.getAttempt()),
                                Objects.requireNonNull(job.getRunId()),
                                work,
                                work.plus(Duration.ofMinutes(10))));

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.authentication()).isNull();
    }

    @Test
    void shouldRejectJobJwtBoundToAnotherWorkspace() throws Exception {
        ReviewJob job = runningJob(true);

        Instant work = Instant.now().plus(Duration.ofMinutes(5));
        AuthenticationResult result = authenticate(jwtIssuer.issueForReview(
                job.getId(),
                workspace.getId() + 1,
                ReviewRunKind.epoch(job.getAttempt()),
                Objects.requireNonNull(job.getRunId()),
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
        RuntimeJobTestData.reclaim(jdbc, job);

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

        RuntimeJobTestData.leaseUntil(jdbc, job, Instant.now().minusSeconds(1));
        assertThat(authenticate(method, path, token).status()).isEqualTo(409);

        RuntimeJobTestData.reclaim(jdbc, job);
        assertThat(authenticate(method, path, token).status()).isEqualTo(409);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/internal/llm/chat/completions",
                "/internal/llm/practice-script/decision/decisions",
                "/internal/llm/admit-results",
                "/internal/llm/stage-result"
            })
    void shouldAuthenticateTheJobsOneTokenWhenItCallsAnyModelRouteOrAdmission(String path) throws Exception {
        ReviewJob job = reviewJob("https://models.example.com/v1");

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
        ReviewJob job = reviewJob("https://models.example.com/v1");
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

    /** Logos's shape when it fails: the head and a comment at once, and then nothing, with no error. */
    private static DisposableServer silentAfterAComment() {
        return HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> response.header("Content-Type", "text/event-stream")
                        .sendString(Flux.concat(
                                Mono.just(": logos-schedule x-logos-ettft-ms=51\n\n"), Mono.<String>never())))
                .bindNow();
    }

    @Test
    void shouldLowerTheLimitWhenAStreamSendsItsHeadAndACommentAndThenNothing() throws Exception {
        var upstream = silentAfterAComment();
        try {
            var pool = streamUntilTheWorkEnds("http://127.0.0.1:" + upstream.port() + "/v1", 8, 50);
            assertThat(pool.getLearnedLimit()).isEqualTo(5);
            assertThat(pool.getThrottledAt()).isNotNull();
            assertThat(pool.getBaselineFirstTokenMs()).isEqualTo(50);
            assertThat(unansweredDecreases(pool)).isEqualTo(1);
            assertThat(callRows.countActive(pool.getId())).isZero();
            assertThat(calls(pool, "unanswered")).isEqualTo(1);
            assertThat(calls(pool, "completed")).isZero();
        } finally {
            upstream.disposeNow();
        }
    }

    @Test
    void shouldCountAModelsRefusalAsTheOutcomeOfItsCallAndNotAsTheProxysRefusal() throws Exception {
        var upstream = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> response.header("Content-Type", "text/event-stream")
                        .sendString(Flux.just(
                                "data: {\"choices\":[{\"delta\":{\"content\":\"I can't\"}}]}\n\n",
                                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"content_filter\"}]}\n\n",
                                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":4}}\n\n",
                                "data: [DONE]\n\n")))
                .bindNow();
        try {
            var pool = streamUntilTheWorkEnds("http://127.0.0.1:" + upstream.port() + "/v1", 8, null);
            assertThat(calls(pool, "refused")).isEqualTo(1);
            assertThat(calls(pool, "completed")).isZero();
            assertThat(
                            meterRegistry
                                    .get(AgentMetrics.GEN_AI_CLIENT_OPERATION_DURATION)
                                    .tag("pool", pool.getSlug())
                                    .timers()
                                    .stream()
                                    .mapToLong(Timer::count)
                                    .sum())
                    .isEqualTo(1);
            assertThat(tokens(pool, "input")).isEqualTo(11);
            assertThat(tokens(pool, "output")).isEqualTo(4);
            assertThat(meterRegistry.get(AgentMetrics.LLM_PROXY_REFUSED).counters())
                    .allSatisfy(counter -> assertThat(counter.count()).isZero());
        } finally {
            upstream.disposeNow();
        }
    }

    @Test
    void shouldKeepAModelsRefusalThatCameWithoutContentOrUsage() throws Exception {
        var upstream = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> response.header("Content-Type", "text/event-stream")
                        .sendString(Flux.just(
                                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"content_filter\"}]}\n\n",
                                "data: [DONE]\n\n")))
                .bindNow();
        try {
            var pool = streamUntilTheWorkEnds("http://127.0.0.1:" + upstream.port() + "/v1", 8, null);
            assertThat(calls(pool, "refused")).isEqualTo(1);
            assertThat(calls(pool, "unanswered")).isZero();
        } finally {
            upstream.disposeNow();
        }
    }

    @Test
    void shouldNotRaiseTheLimitForASilentStreamBeforeAnyFirstTokenIsKnown() throws Exception {
        var upstream = silentAfterAComment();
        try {
            // At two, one background call binds the limit, which a served call would raise.
            var pool = streamUntilTheWorkEnds("http://127.0.0.1:" + upstream.port() + "/v1", 2, null);
            assertThat(pool.getLearnedLimit()).isEqualTo(2);
            assertThat(pool.getThrottledAt()).isNull();
            assertThat(unansweredDecreases(pool)).isZero();
        } finally {
            upstream.disposeNow();
        }
    }

    @Test
    void shouldLowerTheLimitWithoutABaselineWhenTheSilenceBoundCutsTheStream() throws Exception {
        var upstream = silentAfterAComment();
        try {
            var pool = streamUntilTheWorkEnds(
                    "http://127.0.0.1:" + upstream.port() + "/v1", 8, null, Duration.ofSeconds(1));
            assertThat(pool.getLearnedLimit()).isEqualTo(5);
            assertThat(unansweredDecreases(pool)).isEqualTo(1);
        } finally {
            upstream.disposeNow();
        }
    }

    @Test
    void shouldLowerTheLimitWithoutABaselineWhenAGatewaySendsOnlyCommentsPastTheSilenceBound() throws Exception {
        // A comment every 300 ms keeps the stream from going silent, so only the work deadline ends it.
        var upstream = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> response.header("Content-Type", "text/event-stream")
                        .sendString(Flux.interval(Duration.ofMillis(300)).map(tick -> ": logos-schedule\n\n")))
                .bindNow();
        try {
            var pool = streamUntilTheWorkEnds(
                    "http://127.0.0.1:" + upstream.port() + "/v1", 8, null, Duration.ofSeconds(1));
            assertThat(pool.getLearnedLimit()).isEqualTo(5);
            assertThat(unansweredDecreases(pool)).isEqualTo(1);
        } finally {
            upstream.disposeNow();
        }
    }

    @Test
    void shouldLowerTheLimitAndKeepTheBaselineWhenAStreamEndsWithOnlyAnError() throws Exception {
        try (var upstream = new MockWebServer()) {
            upstream.enqueue(new MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body("data: {\"error\":{\"message\":\"overloaded\"}}\n\n")
                    .build());
            upstream.start();
            var pool = streamUntilTheWorkEnds(upstream.url("/v1").toString(), 8, 700);
            assertThat(pool.getLearnedLimit()).isEqualTo(5);
            assertThat(pool.getBaselineFirstTokenMs()).isEqualTo(700);
            assertThat(pool.getWindowSamples()).isZero();
            assertThat(unansweredDecreases(pool)).isEqualTo(1);
        }
    }

    private LlmCallPool streamUntilTheWorkEnds(String baseUrl, int limit, @Nullable Integer baselineMillis)
            throws Exception {
        return streamUntilTheWorkEnds(baseUrl, limit, baselineMillis, Duration.ofMinutes(2));
    }

    /**
     * One streamed review call on the model's own pool, which ends when the provider, the proxy's silence bound or the
     * attempt's work does.
     */
    private LlmCallPool streamUntilTheWorkEnds(
            String baseUrl, int limit, @Nullable Integer baselineMillis, Duration silence) throws Exception {
        ReviewJob job = reviewJob(baseUrl);
        job = jobRepository.saveAndFlush(job);
        var model = Objects.requireNonNull(
                ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper).model());
        long poolId = modelRepository
                .findById(Objects.requireNonNull(model.modelId()))
                .orElseThrow()
                .getCallPoolId();
        jdbc.update(
                "UPDATE llm_call_pool SET learned_limit=?,baseline_first_token_ms=? WHERE id=?",
                limit,
                baselineMillis,
                poolId);
        var capacity = new LlmCallPermits(
                callPools,
                callRows,
                transactionTemplate,
                new LlmCallPoolProperties(Duration.ofMinutes(1), Duration.ofMinutes(5)),
                LlmCallPermitsTestFixtures.PERMIT_AGING,
                "worker-1",
                meterRegistry,
                modelResolver);
        var service = proxyService(
                WebClient.create(),
                mock(LlmBudgetService.class),
                capacity,
                new HephCallProperties(Duration.ofMinutes(5), silence));
        var request = new MockHttpServletRequest("POST", "/internal/llm/chat/completions");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Authorization", "Bearer " + tokenFor(job, Duration.ofSeconds(3)));
        var response = new MockHttpServletResponse();
        var body = objectMapper.createObjectNode().put("model", "caller-model").put("stream", true);
        body.putArray("messages").addObject().put("role", "user").put("content", "Review.");
        filter.doFilter(
                request,
                response,
                (ignoredRequest, ignoredResponse) ->
                        service.proxy(request, response, new HttpHeaders(), objectMapper.writeValueAsBytes(body)));
        return callPools.findById(poolId).orElseThrow();
    }

    private double calls(LlmCallPool pool, String outcome) {
        return meterRegistry
                .get(AgentMetrics.GEN_AI_CLIENT_CALLS)
                .tag("pool", pool.getSlug())
                .tag("outcome", outcome)
                .counters()
                .stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    private double tokens(LlmCallPool pool, String type) {
        return meterRegistry
                .get(AgentMetrics.GEN_AI_CLIENT_TOKENS)
                .tag("pool", pool.getSlug())
                .tag("type", type)
                .counters()
                .stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    private double unansweredDecreases(LlmCallPool pool) {
        var counter = meterRegistry
                .find(AgentMetrics.LLM_POOL_LIMIT_DECREASES)
                .tag("pool", pool.getSlug())
                .tag("cause", "unanswered")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void shouldForwardTheModelsOutputLimitAndHoldTheCallsUpperBoundWhenTheRequestNamesNone() throws Exception {
        try (MockWebServer upstream = new MockWebServer()) {
            upstream.start();
            upstream.enqueue(json("{\"choices\":[]}"));
            ReviewJob job = reviewJob(upstream.url("/v1").toString());

            assertThat(callChat(proxyService(), job)).isEqualTo(200);

            var sent = Objects.requireNonNull(upstream.takeRequest(5, TimeUnit.SECONDS));
            var forwarded =
                    objectMapper.readTree(Objects.requireNonNull(sent.getBody()).utf8());
            assertThat(forwarded.path("max_tokens").asLong()).isEqualTo(CHAT_OUTPUT_LIMIT);
            // The answer reported no usage: the call ends unverifiable and holds its bound, the request's 64 bytes
            // and the output limit at the frozen $1 per 1M tokens.
            assertThat(calls(job)).containsExactly(new CallRow("ENDED", "UNVERIFIABLE", "UNPRICED", 0, 1));
            assertThat(jdbc.queryForObject(
                            "SELECT cost_bound_usd FROM llm_usage_event WHERE source_id = ?",
                            BigDecimal.class,
                            job.getId()))
                    .isEqualByComparingTo("0.004160");
        }
    }

    /** A client's default of 16,384 output tokens on a model whose limit is lower: the composer's case. */
    @Test
    void shouldClampAChatCallThatAsksForMoreOutputThanItsModelsLimitAndBoundItAtTheLimit() throws Exception {
        try (MockWebServer upstream = new MockWebServer()) {
            upstream.start();
            upstream.enqueue(json("{\"choices\":[]}"));
            ReviewJob job = reviewJob(upstream.url("/v1").toString());
            String request = "{\"max_tokens\":16384,\"messages\":[]}";

            assertThat(callChat(proxyService(), job, request)).isEqualTo(200);

            var sent = Objects.requireNonNull(upstream.takeRequest(5, TimeUnit.SECONDS));
            var forwarded =
                    objectMapper.readTree(Objects.requireNonNull(sent.getBody()).utf8());
            assertThat(forwarded.path("max_tokens").asLong()).isEqualTo(CHAT_OUTPUT_LIMIT);
            // The request's bytes and the clamped limit at the frozen $1 per 1M tokens.
            long bound = (long) request.getBytes(StandardCharsets.UTF_8).length + CHAT_OUTPUT_LIMIT;
            assertThat(jdbc.queryForObject(
                            "SELECT cost_bound_usd FROM llm_usage_event WHERE source_id = ?",
                            BigDecimal.class,
                            job.getId()))
                    .isEqualByComparingTo(BigDecimal.valueOf(bound, 6));
        }
    }

    /**
     * The cap holds each call's upper bound: a call starts only while the month's spend plus its own bound fits under
     * the cap. The call's bound is the request's 64 bytes and the output limit at $1 per 1M tokens: $0.004160.
     */
    @Test
    void shouldRefuseAChatCallWhoseUpperBoundDoesNotFitUnderTheCapAndServeOneThatFits() throws Exception {
        try (MockWebServer upstream = new MockWebServer()) {
            upstream.start();
            upstream.enqueue(json("{\"choices\":[]}"));
            ReviewJob job = reviewJob(upstream.url("/v1").toString());

            assertThat(callChat(proxyService(WebClient.create(), capOf("0.004159"), admittingPermits()), job))
                    .isEqualTo(429);
            assertThat(upstream.getRequestCount()).isZero();
            assertThat(calls(job)).isEmpty();

            assertThat(callChat(proxyService(WebClient.create(), capOf("0.004160"), admittingPermits()), job))
                    .isEqualTo(200);
            assertThat(upstream.getRequestCount()).isEqualTo(1);
        }
    }

    // The worker role is off here; supply the ledger's production transaction boundary explicitly.

    private LlmProxyService proxyService(WebClient client) {
        return proxyService(client, mock(LlmBudgetService.class), admittingPermits());
    }

    // One dollar, at the one dollar per million input tokens that reviewJob freezes.
    private static final String ONE_DOLLAR_ANSWER = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
            + "\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":1000000,\"completion_tokens\":0}}";

    private int callChat(LlmProxyService service, ReviewJob job) throws Exception {
        return callChat(service, job, "{\"model\":\"any\",\"messages\":[{\"role\":\"user\",\"content\":\"Review.\"}]}");
    }

    private int callChat(LlmProxyService service, ReviewJob job, String json) throws Exception {
        var request = new MockHttpServletRequest("POST", "/internal/llm/chat/completions");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Authorization", "Bearer " + tokenFor(job));
        var response = new MockHttpServletResponse();
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        AtomicReference<@Nullable ResponseEntity<?>> served = new AtomicReference<>();
        filter.doFilter(
                request,
                response,
                (filtered, ignored) -> served.set(service.proxy(request, response, new HttpHeaders(), body)));
        var answer = served.get();
        return answer == null ? response.getStatus() : answer.getStatusCode().value();
    }

    private static Dispatcher alwaysOneDollar() {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return json(ONE_DOLLAR_ANSWER);
            }
        };
    }

    /** A shared-model cap of {@code usd}, against what the workspace's ended calls cost when it is read. */
    private LlmBudgetService capOf(String usd) {
        var budget = mock(LlmBudgetService.class);
        long workspaceId = workspace.getId();
        when(budget.headroom(workspaceId))
                .thenAnswer(read -> new LlmBudgetHeadroom(
                        Objects.requireNonNull(jdbc.queryForObject(
                                "SELECT COALESCE(SUM(cost_usd), 0) FROM llm_usage_event WHERE workspace_id = ?"
                                        + " AND pricing_state = 'PRICED' AND funding_source = 'INSTANCE'",
                                BigDecimal.class,
                                workspaceId)),
                        new BigDecimal(usd),
                        false,
                        null,
                        null,
                        false));
        return budget;
    }

    // The bound: each attempt can have one call out when the cap is crossed, and no attempt spends on its own count.
    @Test
    void shouldHoldTheWorkspaceWithinItsBoundWhenItsAttemptsSpendAtOnce() throws Exception {
        int attempts = 6;
        int capUsd = 3;
        try (MockWebServer upstream = new MockWebServer()) {
            upstream.setDispatcher(alwaysOneDollar());
            upstream.start();
            var service = proxyService(WebClient.create(), capOf(Integer.toString(capUsd)), admittingPermits());
            List<ReviewJob> jobs = new ArrayList<>();
            for (int attempt = 0; attempt < attempts; attempt++) {
                jobs.add(reviewJob(upstream.url("/v1").toString()));
            }
            try (var requests = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<Integer>> spenders = new ArrayList<>();
                for (ReviewJob job : jobs) {
                    spenders.add(requests.submit(() -> {
                        int status = callChat(service, job);
                        for (int calls = 1; status == 200 && calls < 4 * capUsd; calls++) {
                            status = callChat(service, job);
                        }
                        return status;
                    }));
                }
                for (Future<Integer> spender : spenders) {
                    assertThat(spender.get(60, TimeUnit.SECONDS)).isEqualTo(429);
                }
            }
            assertThat(upstream.getRequestCount()).isBetween(capUsd, capUsd + attempts);
        }
    }

    @Test
    void shouldRefuseACallThatTheCapOvertookWhileItWaitedForCapacity() throws Exception {
        try (MockWebServer upstream = new MockWebServer()) {
            upstream.setDispatcher(alwaysOneDollar());
            upstream.start();
            ReviewJob waiting = reviewJob(upstream.url("/v1").toString());
            ReviewJob spender = reviewJob(upstream.url("/v1").toString());
            var capacity = mock(LlmCallPermits.class);
            var permit = mock(LlmCallPermits.Permit.class);
            when(permit.revoked()).thenReturn(Mono.never());
            var service = proxyService(WebClient.create(), capOf("1"), capacity);
            when(capacity.acquire(anyLong(), any(), any(), any(), any())).thenAnswer(wait -> {
                LlmCallPermits.Caller caller = wait.getArgument(2);
                if (caller.sourceId().equals(waiting.getId())) {
                    try (var other = Executors.newVirtualThreadPerTaskExecutor()) {
                        assertThat(other.submit(() -> callChat(service, spender))
                                        .get())
                                .isEqualTo(200);
                    }
                }
                return permit;
            });

            assertThat(callChat(service, waiting)).isEqualTo(429);

            assertThat(upstream.getRequestCount()).isEqualTo(1);
        }
    }

    /** A pool that admits every call, as a model is always in a pool. */
    private static LlmCallPermits admittingPermits() {
        var permits = mock(LlmCallPermits.class);
        var permit = mock(LlmCallPermits.Permit.class);
        when(permit.revoked()).thenReturn(Mono.never());
        when(permits.acquire(anyLong(), any(), any(), any(), any())).thenReturn(permit);
        Mockito.lenient()
                .when(permits.tryAcquireBackground(anyLong(), any(), any(), any()))
                .thenReturn(permit);
        return permits;
    }

    private LlmProxyService proxyService(WebClient client, LlmBudgetService budgetService, LlmCallPermits capacity) {
        return proxyService(
                client, budgetService, capacity, new HephCallProperties(Duration.ofMinutes(5), Duration.ofMinutes(2)));
    }

    private LlmProxyService proxyService(
            WebClient client, LlmBudgetService budgetService, LlmCallPermits capacity, HephCallProperties silence) {
        ProxyRequestPolicy policy = mock(ProxyRequestPolicy.class);
        when(policy.allows(any())).thenReturn(true);
        var gate = new ProxyBudgetGate(budgetService);
        var ledger = new ProxyCallLedger(
                modelAdmission,
                draftCalls,
                usageRecorder,
                agentRuns,
                jobRepository,
                turnRepository,
                gate,
                objectMapper,
                meterRegistry,
                transactionManager);
        return new LlmProxyService(
                Tracer.NOOP,
                client,
                modelResolver,
                objectMapper,
                new ProxyAccounting(gate, ledger, meterRegistry, objectMapper),
                policy,
                mock(ScanCallGuard.class),
                new ProxyRoutingService(
                        jobRepository, turnRepository, objectMapper, reviewSnapshots, agentRuns, draftCalls),
                new ProxyCallsInFlight(12),
                new LlmConnectionHealth(CircuitBreakerRegistry.ofDefaults()),
                capacity,
                silence);
    }

    /** Each call row of the job's first attempt. */
    private List<CallRow> calls(ReviewJob job) {
        return jdbc.query(
                "SELECT call_state, measurement_verification, pricing_state, input_tokens, unreported_calls"
                        + " FROM llm_usage_event WHERE source_id = ? AND source_attempt = 0",
                (row, index) -> new CallRow(
                        Objects.requireNonNull(row.getString("call_state")),
                        Objects.requireNonNull(row.getString("measurement_verification")),
                        Objects.requireNonNull(row.getString("pricing_state")),
                        row.getLong("input_tokens"),
                        row.getInt("unreported_calls")),
                job.getId());
    }

    private record CallRow(String state, String verification, String pricing, long inputTokens, int unreported) {}

    private static MockResponse json(String body) {
        return new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build();
    }

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
        return tokenFor(job, Duration.ofMinutes(5));
    }

    private String tokenFor(ReviewJob job, Duration workLeft) {
        Instant work = Instant.now().plus(workLeft);
        return jwtIssuer.issueForReview(
                job.getId(),
                workspace.getId(),
                ReviewRunKind.epoch(job.getAttempt()),
                Objects.requireNonNull(job.getRunId()),
                work,
                work.plus(Duration.ofMinutes(10)));
    }

    private ProxyRouting routingOf(AuthenticationResult result) {
        Authentication authentication = Objects.requireNonNull(result.authentication(), "token did not authenticate");
        return new ProxyRoutingService(
                        jobRepository, turnRepository, objectMapper, reviewSnapshots, agentRuns, draftCalls)
                .resolve((ProxyIdentity) Objects.requireNonNull(authentication.getPrincipal()));
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
                                null,
                                false),
                        128_000,
                        CHAT_OUTPUT_LIMIT,
                        600)
                .toJson(objectMapper);

        ReviewJob job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());

        job.setStatus(ReviewJobStatus.RUNNING);
        job.setConfigSnapshot(snapshot);
        return jobRepository.save(RuntimeJobTestData.persistParent(jdbc, job));
    }

    private ReviewJob reviewJob(String baseUrl) {
        return reviewJob(baseUrl, LlmApiProtocol.OPENAI_COMPLETIONS);
    }

    private ReviewJob reviewJob(String baseUrl, LlmApiProtocol protocol) {
        LlmConnection connection = LlmCatalogTestFixtures.connection("chat-" + System.nanoTime());
        connection.setBaseUrl(baseUrl);
        connection.setApiProtocol(protocol);
        connection.setAuthMode(LlmAuthMode.BEARER);
        connection.setApiKey("upstream-secret");
        connection = connectionRepository.save(connection);
        LlmModel model = modelRepository.save(
                LlmCatalogTestFixtures.model(connection, "chat-" + System.nanoTime(), "chat-upstream"));
        LlmPriceSnapshot price = new LlmPriceSnapshot(
                UsageFundingSource.INSTANCE,
                UsagePricingState.PRICED,
                null,
                null,
                BigDecimal.ONE,
                BigDecimal.ONE,
                null,
                null);
        ConfigSnapshot snapshot = new ConfigSnapshot(
                ConfigSnapshot.SCHEMA_VERSION,
                new FrozenModel(
                        protocol,
                        baseUrl,
                        "chat-upstream",
                        UsageFundingSource.INSTANCE,
                        connection.getId(),
                        model.getId(),
                        workspace.getId(),
                        DataHandlingTier.UNDECLARED,
                        null,
                        price,
                        false),
                128_000,
                CHAT_OUTPUT_LIMIT,
                600);
        ReviewJob job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setConfigSnapshot(snapshot.toJson(objectMapper));
        return jobRepository.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
    }

    private LlmProxyService proxyService() {
        return proxyService(WebClient.create());
    }
}
