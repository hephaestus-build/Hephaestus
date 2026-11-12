package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenInvalidException;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.attempt.SandboxJwt;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConnector;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayProperties;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunArgumentResolver;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.run.AgentRun;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.BucketResolver;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import tools.jackson.databind.ObjectMapper;

/**
 * The gateway connector's whole point is that a sandbox cannot tell what else the worker serves, so
 * the chains are asserted on the statuses they produce rather than on the matchers they are built
 * from. Everything they need is one properties record, so this stays out of the integration tier —
 * and out of binding a fixed port on a host other worktrees share.
 */
class LlmProxySecurityConfigTest extends BaseUnitTest {
    private static final UUID LEASE = UUID.fromString("1d7225ec-bb81-5a40-b74f-cca37d960e5e");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int GATEWAY_PORT = 9081;
    private static final int APPLICATION_PORT = 9080;
    private static final SandboxGatewayProperties GATEWAY =
            new SandboxGatewayProperties(GATEWAY_PORT, 16, 1, 1000, 4096);

    @Mock
    private ReviewJobRepository jobRepository;

    @Mock
    private AgentRuns agentRuns;

    @Mock
    private AttemptTokenVerifier jwtVerifier;

    @Mock
    private ProxyBudgetGate budgetGate;

    @Mock
    private MentorTurnRepository turnRepository;

    /** The loop's credential, as the test profile sets it. */
    private static final String LOOP_SECRET = "0123456789abcdef".repeat(4);

    @Mock
    private AgentSandboxes agentSandboxes;

    private final Map<String, Bucket> buckets = new HashMap<>();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private long bodyBudgetBytes = 1L << 20;
    private FilterChainProxy chains;
    private final AtomicReference<@Nullable Authentication> servedAs = new AtomicReference<>();
    private final AtomicReference<@Nullable Map<String, String>> servedWithLogContext = new AtomicReference<>();

    @BeforeEach
    void buildChains() throws Exception {
        SecurityContextHolder.clearContext();
        chains = chains(GATEWAY);
    }

    private FilterChainProxy chains(SandboxGatewayProperties gateway) throws Exception {
        return chains(gateway, List.of());
    }

    private FilterChainProxy chains(SandboxGatewayProperties gateway, List<SandboxRunKind> kinds) throws Exception {
        var context = new GenericApplicationContext();
        context.refresh();
        var config = new LlmProxySecurityConfig();
        var accounting = new ProxyAccounting(budgetGate, mock(ProxyCallLedger.class), meterRegistry, OBJECT_MAPPER);
        BucketResolver resolver = (key, configuration) -> buckets.computeIfAbsent(
                key,
                ignored -> Bucket.builder()
                        .addLimit(configuration.getBandwidths()[0])
                        .build());
        var owners = new StaticListableBeanFactory();
        owners.addBean("agentSandboxes", agentSandboxes);
        return new FilterChainProxy(List.of(
                config.agentGatewayFilterChain(
                        httpSecurity(context),
                        gateway,
                        new SandboxGatewayConnector(GATEWAY_PORT, gateway.maxConnections()),
                        jwtVerifier,
                        owners.getBeanProvider(AgentSandboxes.class),
                        new SandboxGatewayBodyBudget(bodyBudgetBytes),
                        accounting,
                        OBJECT_MAPPER),
                config.llmProxyFilterChain(
                        httpSecurity(context),
                        gateway,
                        new SandboxGatewayConnector(GATEWAY_PORT, gateway.maxConnections()),
                        jobRepository,
                        agentRuns,
                        turnRepository,
                        new ConversationLoopProperties(LOOP_SECRET),
                        jwtVerifier,
                        owners.getBeanProvider(AgentSandboxes.class),
                        kinds,
                        resolver,
                        new SandboxGatewayBodyBudget(bodyBudgetBytes),
                        accounting,
                        OBJECT_MAPPER),
                config.hideNonGatewayCapabilities(
                        httpSecurity(context),
                        new SandboxGatewayConnector(GATEWAY_PORT, gateway.maxConnections()),
                        accounting),
                config.blockLlmProxyOnOtherConnectors(httpSecurity(context))));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldCorrelateASandboxModelCallAndRestoreItsCallersContext(boolean fail) throws Exception {
        var token = new SandboxJwt(
                UUID.randomUUID(), 7L, "review:9", "jti", Instant.now().plusSeconds(60));
        var live = new AgentSandboxes.LiveSandbox(token.sandboxId(), 7L, "review:9", AgentSandboxKind.REVIEW);
        when(jwtVerifier.verify("sandbox-token")).thenThrow(new AttemptTokenInvalidException("not an attempt", "typ"));
        when(jwtVerifier.verifySandbox("sandbox-token")).thenReturn(token);
        when(agentSandboxes.authenticate(token)).thenReturn(Optional.of(live));
        var run = mock(AgentRun.class);
        when(run.getConcurrencyKey()).thenReturn(live.subjectKey());
        when(run.getKind()).thenReturn(AgentRunKind.REVIEW);
        when(run.getAttempt()).thenReturn(3);
        when(agentRuns.find(new AgentRunRef(LEASE, 7L))).thenReturn(Optional.of(run));
        when(agentRuns.isCurrent(new AgentRunRef(LEASE, 7L), 3)).thenReturn(true);
        var kind = mock(SandboxRunKind.class);
        when(kind.kind()).thenReturn(AgentRunKind.REVIEW);
        when(kind.sandbox()).thenReturn(AgentSandboxKind.REVIEW);
        when(kind.attempt(any()))
                .thenReturn(Optional.of(new ProxyAttempt(
                        ProxyAttempt.Caller.REVIEW,
                        UUID.randomUUID(),
                        7L,
                        2,
                        LEASE,
                        Instant.now().plusSeconds(60))));
        chains = chains(GATEWAY, List.of(kind));
        var previous =
                Map.of(StructuredLogKeys.RUN_ID, "enclosing-run", StructuredLogKeys.REVIEW_ID, "enclosing-review");
        MDC.setContextMap(previous);
        try {
            var request = withToken(request("POST", "/internal/llm/responses", GATEWAY_PORT), "sandbox-token");
            request.addHeader(SandboxRunArgumentResolver.RUN_HEADER, LEASE.toString());
            request.addHeader(SandboxRunArgumentResolver.EPOCH_HEADER, "3");
            if (fail) {
                assertThatThrownBy(
                                () -> chains.doFilter(request, new MockHttpServletResponse(), (incoming, response) -> {
                                    servedWithLogContext.set(MDC.getCopyOfContextMap());
                                    throw new IllegalStateException("model handler failed");
                                }))
                        .isInstanceOf(IllegalStateException.class);
            } else {
                assertThat(answerTo(request)).isEqualTo(200);
            }
            assertThat(servedWithLogContext.get())
                    .containsEntry(StructuredLogKeys.RUN_ID, LEASE.toString())
                    .containsEntry(StructuredLogKeys.RUN_EPOCH, "3")
                    .containsEntry(StructuredLogKeys.JOB_ATTEMPT, "2")
                    .containsEntry(StructuredLogKeys.WORKSPACE_ID, "7")
                    .containsEntry(StructuredLogKeys.SUBJECT_KEY, "review:9")
                    .containsEntry(StructuredLogKeys.REVIEW_ID, "9");
            assertThat(MDC.getCopyOfContextMap()).isEqualTo(previous);
        } finally {
            MDC.clear();
        }
    }

    @Test
    void shouldNotAttributeAConversationRequestToAnEnclosingReview() throws Exception {
        var token = new SandboxJwt(
                UUID.randomUUID(), 7L, "conversation:42", "jti", Instant.now().plusSeconds(60));
        var live =
                new AgentSandboxes.LiveSandbox(token.sandboxId(), 7L, "conversation:42", AgentSandboxKind.CONVERSATION);
        when(jwtVerifier.verifySandbox("sandbox-token")).thenReturn(token);
        when(agentSandboxes.authenticate(token)).thenReturn(Optional.of(live));
        var previous = Map.of(
                StructuredLogKeys.REVIEW_ID,
                "enclosing-review",
                StructuredLogKeys.RUN_ID,
                "enclosing-run",
                StructuredLogKeys.JOB_ID,
                "enclosing-job",
                StructuredLogKeys.JOB_ATTEMPT,
                "7");
        MDC.setContextMap(previous);
        try {
            assertThat(answerTo(withToken(request("POST", "/internal/agent/next", GATEWAY_PORT), "sandbox-token")))
                    .isEqualTo(200);
            assertThat(servedWithLogContext.get())
                    .containsEntry(StructuredLogKeys.SUBJECT_KEY, "conversation:42")
                    .doesNotContainKeys(
                            StructuredLogKeys.REVIEW_ID,
                            StructuredLogKeys.RUN_ID,
                            StructuredLogKeys.RUN_EPOCH,
                            StructuredLogKeys.JOB_ID,
                            StructuredLogKeys.JOB_ATTEMPT);
            assertThat(MDC.getCopyOfContextMap()).isEqualTo(previous);
        } finally {
            MDC.clear();
        }
    }

    @Test
    void shouldRestoreEnclosingLogContextWhenASandboxHandlerFails() throws Exception {
        var token = new SandboxJwt(
                UUID.randomUUID(), 7L, "review:9", "jti", Instant.now().plusSeconds(60));
        var live = new AgentSandboxes.LiveSandbox(token.sandboxId(), 7L, "review:9", AgentSandboxKind.REVIEW);
        when(jwtVerifier.verifySandbox("sandbox-token")).thenReturn(token);
        when(agentSandboxes.authenticate(token)).thenReturn(Optional.of(live));
        var previous = Map.of(
                StructuredLogKeys.RUN_ID,
                "enclosing-run",
                StructuredLogKeys.REVIEW_ID,
                "enclosing-review",
                StructuredLogKeys.WORKSPACE_ID,
                "41");
        MDC.setContextMap(previous);
        try {
            assertThatThrownBy(() -> chains.doFilter(
                            withToken(request("POST", "/internal/agent/next", GATEWAY_PORT), "sandbox-token"),
                            new MockHttpServletResponse(),
                            (request, response) -> {
                                assertThat(MDC.get(StructuredLogKeys.REVIEW_ID)).isEqualTo("9");
                                assertThat(MDC.get(StructuredLogKeys.RUN_ID)).isNull();
                                throw new IllegalStateException("handler failed");
                            }))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(MDC.getCopyOfContextMap()).isEqualTo(previous);
        } finally {
            MDC.clear();
        }
    }

    @Test
    void shouldServeASandboxItsAgentRoutesOnlyOnTheGatewayAndOnlyWhileItsSandboxIsLive() throws Exception {
        var token = new SandboxJwt(
                UUID.randomUUID(), 7L, "review:9", "jti", Instant.now().plusSeconds(60));
        var live = new AgentSandboxes.LiveSandbox(token.sandboxId(), 7L, "review:9", AgentSandboxKind.REVIEW);
        when(jwtVerifier.verifySandbox("sandbox-token")).thenReturn(token);
        when(agentSandboxes.authenticate(token)).thenReturn(Optional.of(live));

        assertThat(answerTo(withToken(request("POST", "/internal/agent/next", GATEWAY_PORT), "sandbox-token")))
                .isEqualTo(200);
        assertThat(servedAs.get()).extracting(Authentication::getPrincipal).isEqualTo(live);
        assertThat(servedWithLogContext.get())
                .as("every record of the request names its sandbox")
                .containsEntry(StructuredLogKeys.SANDBOX_NAME, live.claimName())
                .containsEntry(StructuredLogKeys.SUBJECT_KEY, "review:9")
                .containsEntry(StructuredLogKeys.REVIEW_ID, "9")
                .containsEntry(StructuredLogKeys.WORKSPACE_ID, "7");
        assertThat(MDC.get(StructuredLogKeys.SANDBOX_NAME))
                .as("the request's thread carries no sandbox into the next one")
                .isNull();
        assertThat(answerTo(withToken(request("POST", "/internal/agent/next", APPLICATION_PORT), "sandbox-token")))
                .as("the application connector has no agent route")
                .isEqualTo(404);

        when(agentSandboxes.authenticate(token)).thenReturn(Optional.empty());
        assertThat(answerTo(withToken(request("POST", "/internal/agent/next", GATEWAY_PORT), "sandbox-token")))
                .as("a sandbox that was taken back")
                .isEqualTo(401);
    }

    @Test
    void shouldRefuseAnAttemptTokenAndAMissingTokenOnTheAgentRoutes() throws Exception {
        when(jwtVerifier.verifySandbox("turn-token"))
                .thenThrow(new AttemptTokenInvalidException("token type not allowed", "typ"));

        assertThat(answerTo(withToken(request("POST", "/internal/agent/next", GATEWAY_PORT), "turn-token")))
                .isEqualTo(401);
        assertThat(answerTo("POST", "/internal/agent/next", GATEWAY_PORT)).isEqualTo(401);
        assertThat(refused("unauthenticated")).isEqualTo(2);
    }

    @Test
    void hidesTheActuatorFromTheGatewayConnector() throws Exception {
        assertThat(answerTo("GET", "/actuator/health", GATEWAY_PORT)).isEqualTo(404);
        assertThat(answerTo("GET", "/actuator/prometheus", GATEWAY_PORT)).isEqualTo(404);
    }

    @Test
    void hidesTheApplicationApiFromTheGatewayConnector() throws Exception {
        assertThat(answerTo("GET", "/api/workspaces", GATEWAY_PORT)).isEqualTo(404);
    }

    @Test
    void hidesTheSandboxCapabilitiesFromTheApplicationConnector() throws Exception {
        assertThat(answerTo("POST", "/internal/llm/responses", APPLICATION_PORT))
                .isEqualTo(404);
        assertThat(answerTo("POST", "/internal/llm/admit-results", APPLICATION_PORT))
                .isEqualTo(404);
    }

    /**
     * {@code SANDBOX_API_MAX_REQUEST_BYTES} is enforced on the chain, ahead of authentication, so an
     * oversized body is refused before anything has read or resolved it.
     */
    @Test
    void refusesAnOversizedCapabilityCallBeforeAuthenticatingIt() throws Exception {
        byte[] oversized = new byte[GATEWAY.maxRequestBytes() + 1];
        Arrays.fill(oversized, (byte) ' ');

        assertThat(answerTo("POST", "/internal/llm/responses", GATEWAY_PORT, oversized))
                .isEqualTo(413);
    }

    /** The result upload is bounded by the archive budget, not by the model-call bound. */
    @Test
    void boundsTheResultUploadByTheOutputArchiveBudget() throws Exception {
        String path = "/internal/llm/runtime/" + UUID.randomUUID() + "/result";
        MockHttpServletRequest tooLarge = request("POST", path, GATEWAY_PORT);
        tooLarge.setContent(new byte[GATEWAY.maxRequestBytes() + 1]);
        MockHttpServletRequest undeclared = new MockHttpServletRequest("POST", path);
        undeclared.setLocalPort(GATEWAY_PORT);
        undeclared.addHeader("Transfer-Encoding", "chunked");

        assertThat(answerTo(tooLarge))
                .as("a small archive is not held to the model-call bound")
                .isEqualTo(401);
        assertThat(answerTo(undeclared)).isEqualTo(411);
    }

    /**
     * The one status the gateway does not hide. A sandbox is told where the capabilities are, so a
     * credential that has expired mid-job has to say so — a {@code 404} there would send an operator
     * hunting a routing fault instead of a token.
     */
    @Test
    void answersACapabilityCallWithNoCredentialAsUnauthenticated() throws Exception {
        assertThat(answerTo("POST", "/internal/llm/responses", GATEWAY_PORT)).isEqualTo(401);
    }

    @Test
    void shouldRejectCookieAndQueryCredentialsWhenNoBearerHeaderIsPresent() throws Exception {
        ReviewJob job = runningJobOnAttempt(1);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(jwtVerifier.verify("current-attempt")).thenReturn(jobJwt(job, 1));
        assertThat(answerToTokenCall("current-attempt")).isEqualTo(200);

        for (String path : List.of(
                "/internal/llm/responses",
                "/internal/llm/v1/messages",
                "/internal/llm/chat/completions",
                "/internal/llm/admit-results",
                "/internal/llm/stage-result")) {
            MockHttpServletRequest request = request("POST", path, GATEWAY_PORT);
            request.setCookies(
                    new Cookie("__Host-HEPHAESTUS_AT", "current-attempt"), new Cookie("JSESSIONID", "current-attempt"));
            request.addParameter("access_token", "current-attempt");
            assertThat(answerTo(request)).isEqualTo(401);
            assertThat(servedAs.get()).isNull();
            assertThat(request.getSession(false)).isNull();
        }
    }

    /**
     * The connector-matched chain is the only place a sandbox's credential is checked, so the job a
     * capability call bills has to come from the token that call carried — and a token minted for an
     * attempt the job has since left names no job at all.
     */
    @Test
    void servesACapabilityCallAsTheAttemptItsTokenNames() throws Exception {
        ReviewJob job = runningJobOnAttempt(1);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(jwtVerifier.verify("current-attempt")).thenReturn(jobJwt(job, 1));
        when(jwtVerifier.verify("dead-attempt")).thenReturn(jobJwt(job, 0));

        assertThat(answerToTokenCall("current-attempt")).isEqualTo(200);
        assertThat(servedAs.get())
                .isNotNull()
                .extracting(Authentication::getPrincipal)
                .asInstanceOf(type(ProxyIdentity.class))
                .extracting(ProxyIdentity::description)
                .isEqualTo("job:" + job.getId());

        assertThat(answerToTokenCall("dead-attempt")).isEqualTo(409);
        assertThat(servedAs.get()).isNull();
    }

    @Test
    void shouldServeNoPracticeScriptRouteWhenTheJobTokenCallsIt() throws Exception {
        // The gateway refuses the path before it reads the credential: no capability serves it.
        assertThat(answerTo(withToken(
                        request("POST", "/internal/llm/practice-script/chat/chat/completions", GATEWAY_PORT),
                        "job-token")))
                .isEqualTo(404);
        assertThat(servedAs.get()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "POST,/internal/llm/chat/completions",
        "POST,/internal/llm/responses",
        "POST,/internal/llm/v1/messages",
        "POST,/internal/llm/admit-results",
        "POST,/internal/llm/stage-result",
        "GET,/internal/llm/runtime/{job}",
        "GET,/internal/llm/runtime/{job}/workspace",
        "POST,/internal/llm/runtime/{job}/result"
    })
    void shouldServeEveryCapabilityWhenTheJobsOneTokenCallsIt(String method, String path) throws Exception {
        ReviewJob job = runningJobOnAttempt(1);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(jwtVerifier.verify("job-token")).thenReturn(jobJwt(job, 1));
        MockHttpServletRequest request =
                request(method, path.replace("{job}", job.getId().toString()), GATEWAY_PORT);
        request.addHeader("Authorization", "Bearer job-token");

        assertThat(answerTo(request)).isEqualTo(200);
        assertThat(servedAs.get()).isNotNull();
    }

    @Test
    void shouldRefuseATokenWhenItCarriesOnlyTheRetiredPracticeScriptScope() throws Exception {
        ReviewJob job = runningJobOnAttempt(1);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(jwtVerifier.verify("practice-script-token")).thenReturn(jobJwt(job, 1, "llm_practice_script"));
        MockHttpServletRequest request = request("POST", "/internal/llm/chat/completions", GATEWAY_PORT);
        request.addHeader("Authorization", "Bearer practice-script-token");

        assertThat(answerTo(request)).isEqualTo(403);
        assertThat(servedAs.get()).isNull();
        assertThat(answerToTokenCall("practice-script-token")).isEqualTo(403);
    }

    @Test
    void refusesAJobTokenWithoutTheProxyScope() throws Exception {
        ReviewJob job = runningJobOnAttempt(1);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(jwtVerifier.verify("unscoped")).thenReturn(jobJwt(job, 1, "other"));

        assertThat(answerToTokenCall("unscoped")).isEqualTo(403);
        assertThat(servedAs.get()).isNull();
    }

    @Test
    void shouldServeNoReviewLeaseRenewalBecauseTheWorkerAloneRenewsTheRun() throws Exception {
        ReviewJob job = runningJobOnAttempt(1);
        // The gateway refuses the path before it reads the credential: no capability serves it.
        assertThat(answerTo(withToken(
                        request("POST", "/internal/llm/runtime/" + job.getId() + "/lease", GATEWAY_PORT), "job-token")))
                .isEqualTo(404);
    }

    /** The turn endpoints a Heph host calls with its turn credential, and their methods. */
    private static final List<String[]> TURN_ENDPOINTS = List.of(
            new String[] {"GET", "context"},
            new String[] {"POST", "link-result"},
            new String[] {"POST", "link-problem"},
            new String[] {"POST", "show-feedback"},
            new String[] {"POST", "search"},
            new String[] {"PUT", "partial"},
            new String[] {"POST", "end"});

    @Test
    void shouldServeATurnsModelCallAndItsOwnTurnEndpointsWhenItsTokenCallsThem() throws Exception {
        AttemptJwt turn = turnJwt(2);
        leased(turn, true);

        assertThat(answerTo(withToken(request("POST", "/internal/llm/responses", GATEWAY_PORT), "turn-token")))
                .isEqualTo(200);
        assertThat(servedAs.get())
                .isNotNull()
                .extracting(Authentication::getPrincipal)
                .asInstanceOf(type(ProxyIdentity.class))
                .extracting(ProxyIdentity::description)
                .isEqualTo("mentor-turn:" + turn.id());
        for (String[] endpoint : TURN_ENDPOINTS) {
            // One call a minute is this gateway's limit; each endpoint gets its own minute.
            buckets.clear();
            String path = "/internal/llm/turn/" + turn.id() + "/" + endpoint[1];
            assertThat(answerTo(withToken(request(endpoint[0], path, GATEWAY_PORT), "turn-token")))
                    .as(path)
                    .isEqualTo(200);
            assertThat(servedAs.get())
                    .isNotNull()
                    .extracting(Authentication::getDetails)
                    .isEqualTo(turn);
        }
    }

    @Test
    void shouldRefuseATurnTokenOnAnotherTurnAndOnEveryReviewCapability() throws Exception {
        AttemptJwt turn = turnJwt(1);
        leased(turn, true);

        for (String[] endpoint : TURN_ENDPOINTS) {
            String path = "/internal/llm/turn/" + UUID.randomUUID() + "/" + endpoint[1];
            assertThat(answerTo(withToken(request(endpoint[0], path, GATEWAY_PORT), "turn-token")))
                    .as(path)
                    .isEqualTo(401);
            assertThat(servedAs.get()).isNull();
        }
        for (String path : List.of("/internal/llm/admit-results", "/internal/llm/stage-result")) {
            assertThat(answerTo(withToken(request("POST", path, GATEWAY_PORT), "turn-token")))
                    .as(path)
                    .isEqualTo(403);
            assertThat(servedAs.get()).isNull();
        }
        for (String path :
                List.of("/internal/llm/runtime/" + turn.id(), "/internal/llm/runtime/" + turn.id() + "/workspace")) {
            assertThat(answerTo(withToken(request("GET", path, GATEWAY_PORT), "turn-token")))
                    .as(path)
                    .isEqualTo(401);
        }
    }

    @Test
    void shouldAnswerConflictToTheModelCallOfAnAttemptThatNoLongerHoldsTheLease() throws Exception {
        AttemptJwt turn = turnJwt(2);
        leased(turn, false);

        assertThat(answerTo(withToken(request("POST", "/internal/llm/responses", GATEWAY_PORT), "turn-token")))
                .isEqualTo(409);
        assertThat(servedAs.get()).isNull();
    }

    @Test
    void shouldRefuseATurnTokenWithoutTheProxyScope() throws Exception {
        AttemptJwt turn = turnJwt(1, "other");
        leased(turn, true);

        assertThat(answerTo(withToken(request("POST", "/internal/llm/responses", GATEWAY_PORT), "turn-token")))
                .isEqualTo(403);
        assertThat(answerTo(withToken(
                        request("POST", "/internal/llm/turn/" + turn.id() + "/end", GATEWAY_PORT), "turn-token")))
                .isEqualTo(403);
    }

    /**
     * A review session is registered with its signed job token. A revoked or expired job token is rejected by the
     * signature check and must stay rejected, even while the review's session is open.
     */
    @ParameterizedTest
    @ValueSource(strings = {"revoked", "expired"})
    void shouldRefuseARejectedJobTokenWhenItsReviewSessionIsStillOpen(String reason, @TempDir Path directory)
            throws Exception {
        var sessions = new SandboxGatewaySessions();
        UUID job = UUID.randomUUID();
        String token = reason + "-job-token";
        when(jwtVerifier.verify(token)).thenThrow(new AttemptTokenInvalidException("job token " + reason, reason));
        try (var review = sessions.register(job, token, Files.createFile(directory.resolve("input.tar")), "out")) {
            assertThat(review.id()).isEqualTo(job);
            for (String suffix : List.of("", "/workspace")) {
                String path = "/internal/llm/runtime/" + job + suffix;
                assertThat(answerTo(withToken(request("GET", path, GATEWAY_PORT), token)))
                        .as(path)
                        .isEqualTo(401);
                assertThat(servedAs.get()).isNull();
            }
        }
    }

    @Test
    void shouldServeTheLoopsCredentialOnlyItsAssignmentPull() throws Exception {
        assertThat(answerTo(withToken(request("POST", "/internal/llm/turn-assignments", GATEWAY_PORT), LOOP_SECRET)))
                .isEqualTo(200);
        assertThat(servedAs.get())
                .isNotNull()
                .extracting(Authentication::getPrincipal)
                .asInstanceOf(type(ProxyIdentity.class))
                .extracting(ProxyIdentity::description)
                .isEqualTo("conversation-loop");

        servedAs.set(null);
        assertThat(answerTo(withToken(request("POST", "/internal/llm/turn-assignments", GATEWAY_PORT), "f".repeat(64))))
                .isEqualTo(401);
        when(jwtVerifier.verify(LOOP_SECRET)).thenThrow(new AttemptTokenInvalidException("unsigned", "malformed"));
        assertThat(answerTo(withToken(
                        request("GET", "/internal/llm/turn/" + UUID.randomUUID() + "/context", GATEWAY_PORT),
                        LOOP_SECRET)))
                .isEqualTo(401);
        assertThat(answerTo(withToken(request("POST", "/internal/llm/responses", GATEWAY_PORT), LOOP_SECRET)))
                .isEqualTo(401);
        assertThat(servedAs.get()).isNull();
    }

    /** The pull compares the loop's credential alone: an attempt token there is never verified. */
    @Test
    void shouldRefuseAnAttemptTokenOnTheAssignmentPull() throws Exception {
        assertThat(answerTo(withToken(request("POST", "/internal/llm/turn-assignments", GATEWAY_PORT), "job-token")))
                .isEqualTo(401);
        assertThat(servedAs.get()).isNull();
        verifyNoInteractions(jwtVerifier);
    }

    @Test
    void shouldRefuseAJobTokenOnTurnEndpointsAndServeNoCommandChannel() throws Exception {
        ReviewJob job = runningJobOnAttempt(1);
        when(jwtVerifier.verify("job-token")).thenReturn(jobJwt(job, 1));

        assertThat(answerTo(withToken(
                        request("GET", "/internal/llm/runtime/" + job.getId() + "/commands", GATEWAY_PORT),
                        "job-token")))
                .as("the gateway has no command channel")
                .isEqualTo(404);
        assertThat(answerTo(withToken(
                        request("POST", "/internal/llm/turn/" + UUID.randomUUID() + "/end", GATEWAY_PORT),
                        "job-token")))
                .isEqualTo(401);
    }

    @Test
    void shouldExemptOnlyTheTurnBookkeepingFromTheRateLimit() throws Exception {
        AttemptJwt turn = turnJwt(1);
        leased(turn, true);
        String partial = "/internal/llm/turn/" + turn.id() + "/partial";

        for (int i = 0; i < 5; i++) {
            assertThat(answerTo(withToken(request("PUT", partial, GATEWAY_PORT), "turn-token")))
                    .as("partial %d", i)
                    .isEqualTo(200);
        }
        String search = "/internal/llm/turn/" + turn.id() + "/search";
        assertThat(answerTo(withToken(request("POST", search, GATEWAY_PORT), "turn-token")))
                .isEqualTo(200);
        assertThat(answerTo(withToken(request("POST", search, GATEWAY_PORT), "turn-token")))
                .as("a tool call of the same turn stays limited")
                .isEqualTo(429);
    }

    /** A spent source costs nothing more: its token is not read, whether it is valid or not. */
    @Test
    void shouldRefuseASpentSourceBeforeItsCredentialIsRead() throws Exception {
        chains = chains(new SandboxGatewayProperties(GATEWAY_PORT, 16, 1000, 1, 4096));
        when(jwtVerifier.verify("forged")).thenThrow(new AttemptTokenInvalidException("forged", "kid"));

        assertThat(answerToTokenCall("forged")).isEqualTo(401);
        assertThat(answerToTokenCall("forged")).isEqualTo(429);

        verify(jwtVerifier, times(1)).verify("forged");
        assertThat(refused("source_limit")).isEqualTo(1);
    }

    @Test
    void shouldGiveEachSourceAddressItsOwnBudget() throws Exception {
        chains = chains(new SandboxGatewayProperties(GATEWAY_PORT, 16, 1000, 1, 4096));
        when(jwtVerifier.verify("forged")).thenThrow(new AttemptTokenInvalidException("forged", "kid"));

        assertThat(answerToTokenCall("forged")).isEqualTo(401);
        MockHttpServletRequest other = withToken(request("POST", "/internal/llm/responses", GATEWAY_PORT), "forged");
        other.setAttribute(SandboxGatewayConnector.PEER_ATTRIBUTE, "172.18.0.6");

        assertThat(answerTo(other)).isEqualTo(401);
    }

    /** Each refusal of the gateway shows under its reason, so an operator can tell a probe from a busy review. */
    @Test
    void shouldCountEachRefusalUnderItsReason() throws Exception {
        AttemptJwt turn = turnJwt(1);
        leased(turn, false);
        when(jwtVerifier.verify("forged")).thenThrow(new AttemptTokenInvalidException("forged", "kid"));

        assertThat(answerTo("GET", "/actuator/health", GATEWAY_PORT)).isEqualTo(404);
        assertThat(refused("unknown_route")).isEqualTo(1);

        assertThat(answerTo("POST", "/internal/llm/responses", GATEWAY_PORT, new byte[GATEWAY.maxRequestBytes() + 1]))
                .isEqualTo(413);
        assertThat(refused("oversized")).isEqualTo(1);

        assertThat(answerToTokenCall("forged")).isEqualTo(401);
        assertThat(refused("unauthenticated")).isEqualTo(1);

        assertThat(answerToTokenCall("turn-token")).isEqualTo(409);
        assertThat(refused("attempt_ended")).isEqualTo(1);

        leased(turn, true);
        assertThat(answerToTokenCall("turn-token")).isEqualTo(200);
        assertThat(answerToTokenCall("turn-token")).isEqualTo(429);
        assertThat(refused("principal_limit")).isEqualTo(1);

        assertThat(answerTo(withToken(request("POST", "/internal/llm/admit-results", GATEWAY_PORT), "turn-token")))
                .isEqualTo(403);
        assertThat(refused("forbidden")).isEqualTo(1);
    }

    /** Connections do not bound memory: a body that would pass the budget waits for the ones in flight. */
    @Test
    void shouldRefuseABodyPastTheBudgetAndGiveTheBudgetBackAfterACall() throws Exception {
        bodyBudgetBytes = 1024;
        chains = chains(GATEWAY);
        AttemptJwt turn = turnJwt(1);
        leased(turn, true);
        MockHttpServletRequest held = withToken(request("POST", "/internal/llm/responses", GATEWAY_PORT), "turn-token");
        held.setContent(new byte[16]);
        MockHttpServletRequest refused =
                withToken(request("POST", "/internal/llm/responses", GATEWAY_PORT), "turn-token");
        refused.setContent(new byte[16]);
        var answer = new MockHttpServletResponse();

        chains.doFilter(held, new MockHttpServletResponse(), (ignored, ignoredToo) -> {
            try {
                chains.doFilter(refused, answer, (first, second) -> {});
            } catch (ServletException | IOException e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(answer.getStatus()).isEqualTo(429);
        assertThat(answer.getHeader("Retry-After")).isNotBlank();
        assertThat(answer.getHeader(LlmProxyService.REFUSAL_HEADER)).isEqualTo("CALLS_IN_FLIGHT");
        assertThat(refused("body_budget")).isEqualTo(1);
        buckets.clear();
        assertThat(answerToTokenCall("turn-token")).isEqualTo(200);
    }

    /** A failed read in authentication lands here as an error dispatch, and that is no sandbox probing the gateway. */
    @Test
    void shouldNotCountAnErrorDispatchAsAnUnknownRoute() throws Exception {
        MockHttpServletRequest failed = request("GET", "/error", GATEWAY_PORT);
        failed.setDispatcherType(DispatcherType.ERROR);

        assertThat(answerTo(failed)).isEqualTo(404);

        assertThat(refused("unknown_route")).isZero();
    }

    /** A capability hidden from the application connector is no refusal of the sandbox gateway. */
    @Test
    void shouldNotCountARefusalOnTheApplicationConnector() throws Exception {
        assertThat(answerTo("POST", "/internal/llm/responses", APPLICATION_PORT))
                .isEqualTo(404);

        assertThat(refused("unknown_route")).isZero();
    }

    private double refused(String reason) {
        return meterRegistry
                .get(AgentMetrics.SANDBOX_GATEWAY_REFUSED)
                .tag("reason", reason)
                .counter()
                .count();
    }

    private AttemptJwt turnJwt(int attempt) {
        return turnJwt(attempt, "llm_proxy");
    }

    private AttemptJwt turnJwt(int attempt, String scope) {
        Instant now = Instant.now();
        AttemptJwt turn = new AttemptJwt(
                AttemptJwt.Kind.TURN,
                UUID.randomUUID(),
                7L,
                attempt,
                UUID.randomUUID(),
                Set.of(scope),
                "jti",
                now,
                now.plusSeconds(60),
                now.plusSeconds(30));
        when(jwtVerifier.verify("turn-token")).thenReturn(turn);
        return turn;
    }

    /** Whether the turn's credential still names its attempt's live lease; a turn endpoint reads no row. */
    private void leased(AttemptJwt turn, boolean live) {
        Mockito.lenient()
                .when(turnRepository.findByIdAndWorkspaceId(turn.id(), turn.workspaceId()))
                .thenReturn(Optional.of(Mockito.mock(MentorTurn.class)));
        Mockito.lenient()
                .when(turnRepository.hasLiveLease(turn.id(), turn.workspaceId(), turn.epoch(), turn.runId()))
                .thenReturn(live);
    }

    private static MockHttpServletRequest withToken(MockHttpServletRequest request, String token) {
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private int answerTo(String method, String path, int localPort) throws Exception {
        return answerTo(request(method, path, localPort));
    }

    private int answerTo(String method, String path, int localPort, byte[] body) throws Exception {
        MockHttpServletRequest request = request(method, path, localPort);
        request.setContent(body);
        return answerTo(request);
    }

    /** A capability call carrying a proxy-scoped bearer token, as a sandbox sends it. */
    private int answerToTokenCall(String token) throws Exception {
        MockHttpServletRequest request = request("POST", "/internal/llm/responses", GATEWAY_PORT);
        request.addHeader("Authorization", "Bearer " + token);
        return answerTo(request);
    }

    private static MockHttpServletRequest request(String method, String path, int localPort) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setLocalPort(localPort);
        request.setRemoteAddr("172.18.0.5");
        request.setAttribute(SandboxGatewayConnector.PEER_ATTRIBUTE, "172.18.0.5");
        // Give even an empty request a declared length: the size filter refuses a request with none,
        // which would mask every status under test.
        request.setContent(new byte[0]);
        return request;
    }

    private int answerTo(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        servedAs.set(null);

        chains.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            servedAs.set(SecurityContextHolder.getContext().getAuthentication());
            servedWithLogContext.set(MDC.getCopyOfContextMap());
        });

        if (response.getStatus() == 409) {
            assertThat(response.getContentAsString())
                    .as("an attempt that no longer runs learns only that")
                    .isEqualTo(JobTokenAuthenticationFilter.ATTEMPT_ENDED);
        } else if (response.getStatus() != 429) {
            assertThat(response.getContentAsByteArray())
                    .as("a hidden route reveals nothing in its body")
                    .isEmpty();
        }
        return response.getStatus();
    }

    private ReviewJob runningJobOnAttempt(int attempt) {
        Workspace workspace = new Workspace();
        workspace.setId(7L);
        ReviewJob job = new ReviewJob();
        job.setId(UUID.randomUUID());
        RuntimeJobTestData.initialize(job, workspace.getId());
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setRunId(LEASE);
        RuntimeJobTestData.attempt(job, attempt);
        // Only a credential of this attempt's own lease holds it.
        Mockito.lenient()
                .when(agentRuns.isCurrent(
                        new AgentRunRef(job.getRunId(), workspace.getId()), ReviewRunKind.epoch(attempt)))
                .thenReturn(true);
        job.setConfigSnapshot(new ConfigSnapshot(
                        ConfigSnapshot.SCHEMA_VERSION,
                        new FrozenModel(
                                LlmApiProtocol.parse("openai-responses").orElseThrow(),
                                "https://api.example.com/v1",
                                "catalog-model",
                                null,
                                null,
                                null,
                                workspace.getId(),
                                null,
                                null,
                                null,
                                false),
                        null,
                        null,
                        600)
                .toJson(OBJECT_MAPPER));
        return job;
    }

    private static AttemptJwt jobJwt(ReviewJob job, int attempt) {
        return jobJwt(job, attempt, "llm_proxy");
    }

    private static AttemptJwt jobJwt(ReviewJob job, int attempt, String scope) {
        Instant now = Instant.now();
        return new AttemptJwt(
                AttemptJwt.Kind.REVIEW,
                job.getId(),
                job.getWorkspaceId(),
                ReviewRunKind.epoch(attempt),
                Objects.requireNonNull(job.getRunId()),
                Set.of(scope),
                UUID.randomUUID().toString(),
                now,
                now.plusSeconds(60),
                now.plusSeconds(30));
    }

    private static HttpSecurity httpSecurity(ApplicationContext context) {
        ObjectPostProcessor<Object> postProcessor = new ObjectPostProcessor<>() {
            @Override
            public <O> O postProcess(O object) {
                return object;
            }
        };
        return new HttpSecurity(
                postProcessor,
                new AuthenticationManagerBuilder(postProcessor),
                Map.of(ApplicationContext.class, context));
    }
}
