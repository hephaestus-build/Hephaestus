package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.type;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenInvalidException;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConnector;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayProperties;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.testing.WorkerPropertiesFixtures;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.BucketResolver;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int GATEWAY_PORT = 9081;
    private static final int APPLICATION_PORT = 9080;
    private static final SandboxGatewayProperties GATEWAY = new SandboxGatewayProperties(GATEWAY_PORT, 16, 1);

    @Mock
    private ReviewJobRepository jobRepository;

    @Mock
    private AttemptTokenVerifier jwtVerifier;

    @Mock
    private ProxyBudgetGate budgetGate;

    @Mock
    private ProxyUsageAccumulator usageAccumulator;

    @Mock
    private MentorTurnUsageAccumulator mentorTurnUsageAccumulator;

    @Mock
    private MentorTurnRepository turnRepository;

    @Mock
    private SandboxGatewaySessions gatewaySessions;

    private final Map<String, Bucket> buckets = new HashMap<>();
    private FilterChainProxy chains;
    private final AtomicReference<@Nullable Authentication> servedAs = new AtomicReference<>();

    @BeforeEach
    void buildChains() throws Exception {
        SecurityContextHolder.clearContext();
        chains = chains(gatewaySessions);
    }

    private FilterChainProxy chains(SandboxGatewaySessions sessions) throws Exception {
        var context = new GenericApplicationContext();
        context.refresh();
        var config = new LlmProxySecurityConfig();
        var accounting = new ProxyAccounting(
                budgetGate, usageAccumulator, mentorTurnUsageAccumulator, new SimpleMeterRegistry(), OBJECT_MAPPER);
        BucketResolver resolver = (key, configuration) -> buckets.computeIfAbsent(
                key,
                ignored -> Bucket.builder()
                        .addLimit(configuration.getBandwidths()[0])
                        .build());
        return new FilterChainProxy(List.of(
                config.llmProxyFilterChain(
                        httpSecurity(context),
                        GATEWAY,
                        new SandboxGatewayConnector(GATEWAY_PORT),
                        jobRepository,
                        turnRepository,
                        sessions,
                        jwtVerifier,
                        resolver,
                        accounting,
                        OBJECT_MAPPER,
                        WorkerPropertiesFixtures.minimal("1", "1")),
                config.hideNonGatewayCapabilities(httpSecurity(context), new SandboxGatewayConnector(GATEWAY_PORT)),
                config.blockLlmProxyOnOtherConnectors(httpSecurity(context))));
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

        for (String path :
                List.of("/internal/llm/responses", "/internal/llm/chat/completions", "/internal/llm/admit-results")) {
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
    void shouldServeThePracticeScriptRouteOnTheGatewayWhenTheJobTokenCallsIt() throws Exception {
        String path = "/internal/llm/practice-script/chat/chat/completions";
        ReviewJob job = runningJobOnAttempt(1);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(jwtVerifier.verify("job-token")).thenReturn(jobJwt(job, 1));

        MockHttpServletRequest practiceScript = request("POST", path, GATEWAY_PORT);
        practiceScript.addHeader("Authorization", "Bearer job-token");
        assertThat(answerTo(practiceScript)).isEqualTo(200);
        assertThat(servedAs.get())
                .isNotNull()
                .extracting(Authentication::getPrincipal)
                .asInstanceOf(type(ProxyIdentity.class))
                .extracting(ProxyIdentity::description)
                .isEqualTo("job:" + job.getId());
        assertThat(answerTo("POST", path, APPLICATION_PORT)).isEqualTo(404);
        assertThat(answerTo("POST", path, GATEWAY_PORT, new byte[GATEWAY.maxRequestBytes() + 1]))
                .isEqualTo(413);
    }

    @ParameterizedTest
    @CsvSource({
        "POST,/internal/llm/chat/completions",
        "POST,/internal/llm/responses",
        "POST,/internal/llm/practice-script/chat/chat/completions",
        "POST,/internal/llm/admit-results",
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
        MockHttpServletRequest request =
                request("POST", "/internal/llm/practice-script/chat/chat/completions", GATEWAY_PORT);
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
        leased(turn, turn.attempt(), "test-worker");

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
        leased(turn, turn.attempt(), "test-worker");

        for (String[] endpoint : TURN_ENDPOINTS) {
            String path = "/internal/llm/turn/" + UUID.randomUUID() + "/" + endpoint[1];
            assertThat(answerTo(withToken(request(endpoint[0], path, GATEWAY_PORT), "turn-token")))
                    .as(path)
                    .isEqualTo(401);
            assertThat(servedAs.get()).isNull();
        }
        for (String path :
                List.of("/internal/llm/practice-script/chat/chat/completions", "/internal/llm/admit-results")) {
            assertThat(answerTo(withToken(request("POST", path, GATEWAY_PORT), "turn-token")))
                    .as(path)
                    .isEqualTo(403);
            assertThat(servedAs.get()).isNull();
        }
        for (String path : List.of(
                "/internal/llm/runtime/" + turn.id(),
                "/internal/llm/runtime/" + turn.id() + "/workspace",
                "/internal/llm/runtime/" + turn.id() + "/commands")) {
            assertThat(answerTo(withToken(request("GET", path, GATEWAY_PORT), "turn-token")))
                    .as(path)
                    .isEqualTo(401);
        }
    }

    @ParameterizedTest
    @CsvSource({"3,test-worker", "2,other-worker"})
    void shouldAnswerConflictToTheModelCallOfAnAttemptThatNoLongerHoldsTheLease(int attempt, String worker)
            throws Exception {
        AttemptJwt turn = turnJwt(2);
        leased(turn, attempt, worker);

        assertThat(answerTo(withToken(request("POST", "/internal/llm/responses", GATEWAY_PORT), "turn-token")))
                .isEqualTo(409);
        assertThat(servedAs.get()).isNull();
    }

    @Test
    void shouldRefuseATurnTokenWithoutTheProxyScope() throws Exception {
        AttemptJwt turn = turnJwt(1, "other");
        leased(turn, turn.attempt(), "test-worker");

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
        chains = chains(sessions);
        UUID job = UUID.randomUUID();
        String token = reason + "-job-token";
        when(jwtVerifier.verify(token)).thenThrow(new AttemptTokenInvalidException("job token " + reason, reason));
        try (var review = sessions.register(job, token, Files.createFile(directory.resolve("input.tar")), "out")) {
            assertThat(review.id()).isEqualTo(job);
            for (String suffix : List.of("", "/workspace", "/commands")) {
                String path = "/internal/llm/runtime/" + job + suffix;
                assertThat(answerTo(withToken(request("GET", path, GATEWAY_PORT), token)))
                        .as(path)
                        .isEqualTo(401);
                assertThat(servedAs.get()).isNull();
            }
        }
    }

    @Test
    void shouldServeAHostSessionOnlyItsOwnRuntimeReadsAndCommandChannel() throws Exception {
        // The host's workspace initializer downloads with the session token before the host starts. One session per
        // path, because the test gateway allows one call per minute and caller.
        for (String suffix : List.of("", "/workspace", "/commands")) {
            UUID host = UUID.randomUUID();
            String token = "session-token-" + host;
            when(jwtVerifier.verify(token)).thenThrow(new AttemptTokenInvalidException("unsigned", "malformed"));
            when(gatewaySessions.unsignedSessionFor(token))
                    .thenReturn(Optional.of(
                            new SandboxGatewaySessions.UnsignedSession(host, SandboxGatewaySessions.Kind.HEPH_HOST)));
            servedAs.set(null);
            String path = "/internal/llm/runtime/" + host + suffix;
            assertThat(answerTo(withToken(request("GET", path, GATEWAY_PORT), token)))
                    .as(path)
                    .isEqualTo(200);
            assertThat(servedAs.get())
                    .isNotNull()
                    .extracting(Authentication::getPrincipal)
                    .asInstanceOf(type(ProxyIdentity.class))
                    .extracting(ProxyIdentity::description)
                    .isEqualTo("gateway-session:" + host);
        }
        UUID host = UUID.randomUUID();
        when(jwtVerifier.verify("session-token")).thenThrow(new AttemptTokenInvalidException("unsigned", "malformed"));
        when(gatewaySessions.unsignedSessionFor("session-token"))
                .thenReturn(Optional.of(
                        new SandboxGatewaySessions.UnsignedSession(host, SandboxGatewaySessions.Kind.HEPH_HOST)));
        UUID other = UUID.randomUUID();
        for (String path : List.of(
                "/internal/llm/runtime/" + other,
                "/internal/llm/runtime/" + other + "/workspace",
                "/internal/llm/runtime/" + other + "/commands")) {
            assertThat(answerTo(withToken(request("GET", path, GATEWAY_PORT), "session-token")))
                    .as(path)
                    .isEqualTo(401);
        }
        assertThat(answerTo(withToken(
                        request("POST", "/internal/llm/runtime/" + host + "/result", GATEWAY_PORT), "session-token")))
                .isEqualTo(401);
        assertThat(answerTo(withToken(request("POST", "/internal/llm/responses", GATEWAY_PORT), "session-token")))
                .isEqualTo(401);
        assertThat(answerTo(withToken(
                        request("POST", "/internal/llm/turn/" + host + "/end", GATEWAY_PORT), "session-token")))
                .isEqualTo(401);
    }

    @Test
    void shouldRefuseAJobTokenOnTheCommandChannelAndOnTurnEndpoints() throws Exception {
        ReviewJob job = runningJobOnAttempt(1);
        when(jwtVerifier.verify("job-token")).thenReturn(jobJwt(job, 1));

        assertThat(answerTo(withToken(
                        request("GET", "/internal/llm/runtime/" + job.getId() + "/commands", GATEWAY_PORT),
                        "job-token")))
                .isEqualTo(401);
        assertThat(answerTo(withToken(
                        request("POST", "/internal/llm/turn/" + UUID.randomUUID() + "/end", GATEWAY_PORT),
                        "job-token")))
                .isEqualTo(401);
    }

    @Test
    void shouldExemptOnlyTheTurnBookkeepingFromTheRateLimit() throws Exception {
        AttemptJwt turn = turnJwt(1);
        leased(turn, turn.attempt(), "test-worker");
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
                Set.of(scope),
                "jti",
                now,
                now.plusSeconds(60),
                null);
        when(jwtVerifier.verify("turn-token")).thenReturn(turn);
        return turn;
    }

    /** The turn row: its attempt and the worker that holds the attempt's lease. */
    private void leased(AttemptJwt turn, int attempt, String worker) {
        MentorTurn row = Mockito.mock(MentorTurn.class);
        // The check stops at the first mismatch, and a turn endpoint reads no row.
        Mockito.lenient().when(row.isLeased()).thenReturn(true);
        Mockito.lenient().when(row.getAttempt()).thenReturn(attempt);
        Mockito.lenient().when(row.getWorkerId()).thenReturn(worker);
        Mockito.lenient()
                .when(turnRepository.findByIdAndWorkspaceId(turn.id(), turn.workspaceId()))
                .thenReturn(Optional.of(row));
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
        // Give even an empty request a declared length: the size filter refuses a request with none,
        // which would mask every status under test.
        request.setContent(new byte[0]);
        return request;
    }

    private int answerTo(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        servedAs.set(null);

        chains.doFilter(
                request,
                response,
                (ignoredRequest, ignoredResponse) ->
                        servedAs.set(SecurityContextHolder.getContext().getAuthentication()));

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
        job.setWorkerId("test-worker");
        job.setAttempt(attempt);
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
                                null),
                        null,
                        null,
                        600,
                        false,
                        null)
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
                attempt,
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
