package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.type;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayProperties;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.testing.WorkerPropertiesFixtures;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.BucketResolver;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.JobJwt;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtVerifier;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.Cookie;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
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
    /** One call per principal and minute, so a call that spends from the wrong bucket shows as a 429. */
    private static final SandboxGatewayProperties GATEWAY = new SandboxGatewayProperties(GATEWAY_PORT, 16, 1);

    @Mock
    private AgentJobRepository jobRepository;

    @Mock
    private WorkerJwtVerifier jwtVerifier;

    @Mock
    private ProxyBudgetGate budgetGate;

    @Mock
    private ProxyUsageAccumulator usageAccumulator;

    @Mock
    private MentorTurnUsageAccumulator mentorTurnUsageAccumulator;

    private final MentorProxyCredentialRegistry mentorRegistry = new MentorProxyCredentialRegistry();
    private final Map<String, Bucket> buckets = new HashMap<>();
    private FilterChainProxy chains;
    private final AtomicReference<@Nullable Authentication> servedAs = new AtomicReference<>();

    @BeforeEach
    void buildChains() throws Exception {
        SecurityContextHolder.clearContext();
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
        chains = new FilterChainProxy(List.of(
                config.llmProxyFilterChain(
                        httpSecurity(context),
                        GATEWAY,
                        jobRepository,
                        jwtVerifier,
                        mentorRegistry,
                        resolver,
                        accounting,
                        OBJECT_MAPPER,
                        WorkerPropertiesFixtures.minimal("1", "1")),
                config.hideNonGatewayCapabilities(httpSecurity(context), GATEWAY),
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
        assertThat(answerTo("POST", "/internal/llm/admit-observations", APPLICATION_PORT))
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
        AgentJob job = runningJobOnAttempt(1);
        when(jobRepository.findByIdWithWorkspace(job.getId())).thenReturn(Optional.of(job));
        when(jwtVerifier.verify("current-attempt")).thenReturn(jobJwt(job, 1));
        assertThat(answerToTokenCall("current-attempt")).isEqualTo(200);

        for (String path : List.of(
                "/internal/llm/responses",
                "/internal/llm/chat/completions",
                "/internal/llm/admit-observations",
                "/internal/llm/public-feedback-history")) {
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
        AgentJob job = runningJobOnAttempt(1);
        when(jobRepository.findByIdWithWorkspace(job.getId())).thenReturn(Optional.of(job));
        when(jwtVerifier.verify("current-attempt")).thenReturn(jobJwt(job, 1));
        when(jwtVerifier.verify("dead-attempt")).thenReturn(jobJwt(job, 0));

        assertThat(answerToTokenCall("current-attempt")).isEqualTo(200);
        assertThat(servedAs.get())
                .isNotNull()
                .extracting(Authentication::getPrincipal)
                .asInstanceOf(type(ProxyRouting.class))
                .extracting(ProxyRouting::principalDescription)
                .isEqualTo("job:" + job.getId());

        assertThat(answerToTokenCall("dead-attempt")).isEqualTo(401);
        assertThat(servedAs.get()).isNull();
    }

    @Test
    void servesPublicHistoryOnlyOnTheGatewayForTheAuthenticatedCurrentAttempt() throws Exception {
        String path = "/internal/llm/public-feedback-history";
        AgentJob job = runningJobOnAttempt(1);
        when(jobRepository.findByIdWithWorkspace(job.getId())).thenReturn(Optional.of(job));
        when(jwtVerifier.verify("current-attempt")).thenReturn(jobJwt(job, 1));
        when(jwtVerifier.verify("dead-attempt")).thenReturn(jobJwt(job, 0));
        MockHttpServletRequest current = request("POST", path, GATEWAY_PORT);
        current.addHeader("Authorization", "Bearer current-attempt");
        assertThat(answerTo(current)).isEqualTo(200);
        assertThat(servedAs.get())
                .isNotNull()
                .extracting(Authentication::getPrincipal)
                .asInstanceOf(type(ProxyRouting.class))
                .extracting(ProxyRouting::principalDescription)
                .isEqualTo("job:" + job.getId());
        MockHttpServletRequest stale = request("POST", path, GATEWAY_PORT);
        stale.addHeader("Authorization", "Bearer dead-attempt");
        assertThat(answerTo(stale)).isEqualTo(401);
        assertThat(servedAs.get()).isNull();
        assertThat(answerTo("POST", path, GATEWAY_PORT)).isEqualTo(401);
        assertThat(answerTo("GET", path, GATEWAY_PORT)).isEqualTo(404);
        assertThat(answerTo("POST", path, APPLICATION_PORT)).isEqualTo(404);
        assertThat(answerTo("POST", path, GATEWAY_PORT, new byte[GATEWAY.maxRequestBytes() + 1]))
                .isEqualTo(413);
    }

    /**
     * The precompute route exists only on the gateway, under the model-call bound, for its own scope. A
     * review token refused there spends nothing from the precompute token's bucket.
     */
    @Test
    void servesThePrecomputeRouteOnlyOnTheGatewayForAPrecomputeToken() throws Exception {
        String path = "/internal/llm/precompute/chat/chat/completions";
        AgentJob job = runningJobOnAttempt(1);
        when(jobRepository.findByIdWithWorkspace(job.getId())).thenReturn(Optional.of(job));
        when(jwtVerifier.verify("precompute")).thenReturn(jobJwt(job, 1, "llm_precompute"));
        when(jwtVerifier.verify("review")).thenReturn(jobJwt(job, 1, "llm_proxy"));

        MockHttpServletRequest review = request("POST", path, GATEWAY_PORT);
        review.addHeader("Authorization", "Bearer review");
        assertThat(answerTo(review)).isEqualTo(403);
        assertThat(servedAs.get()).isNull();
        MockHttpServletRequest precompute = request("POST", path, GATEWAY_PORT);
        precompute.addHeader("Authorization", "Bearer precompute");
        assertThat(answerTo(precompute)).isEqualTo(200);
        assertThat(servedAs.get())
                .isNotNull()
                .extracting(Authentication::getPrincipal)
                .asInstanceOf(type(ProxyRouting.class))
                .extracting(ProxyRouting::principalDescription)
                .isEqualTo("job:" + job.getId() + ":precompute");
        assertThat(answerTo("POST", path, APPLICATION_PORT)).isEqualTo(404);
        assertThat(answerTo("POST", path, GATEWAY_PORT, new byte[GATEWAY.maxRequestBytes() + 1]))
                .isEqualTo(413);
    }

    @ParameterizedTest
    @CsvSource({
        "POST,/internal/llm/chat/completions",
        "POST,/internal/llm/responses",
        "POST,/internal/llm/admit-observations",
        "POST,/internal/llm/public-feedback-history",
        "GET,/internal/llm/runtime/{job}",
        "GET,/internal/llm/runtime/{job}/workspace",
        "GET,/internal/llm/runtime/{job}/frames",
        "POST,/internal/llm/runtime/{job}/result"
    })
    void refusesAPrecomputeTokenOnEveryOtherCapability(String method, String path) throws Exception {
        AgentJob job = runningJobOnAttempt(1);
        when(jobRepository.findByIdWithWorkspace(job.getId())).thenReturn(Optional.of(job));
        when(jwtVerifier.verify("precompute")).thenReturn(jobJwt(job, 1, "llm_precompute"));
        MockHttpServletRequest request =
                request(method, path.replace("{job}", job.getId().toString()), GATEWAY_PORT);
        request.addHeader("Authorization", "Bearer precompute");

        assertThat(answerTo(request)).isEqualTo(403);
        assertThat(servedAs.get()).isNull();
    }

    @Test
    void refusesAJobTokenWithoutTheProxyScope() throws Exception {
        AgentJob job = runningJobOnAttempt(1);
        when(jobRepository.findByIdWithWorkspace(job.getId())).thenReturn(Optional.of(job));
        when(jwtVerifier.verify("unscoped")).thenReturn(jobJwt(job, 1, "other"));

        assertThat(answerToTokenCall("unscoped")).isEqualTo(403);
        assertThat(servedAs.get()).isNull();
    }

    @Test
    void refusesAMentorCredentialOnThePrecomputeRoute() throws Exception {
        String mentorToken = mentorRegistry.mint(
                UUID.randomUUID(),
                new MentorProxyCredentialRegistry.Route(
                        "openai-completions", "https://api.example.com/v1", null, null, null, null));
        MockHttpServletRequest request =
                request("POST", "/internal/llm/precompute/chat/chat/completions", GATEWAY_PORT);
        request.addHeader("Authorization", "Bearer " + mentorToken);

        assertThat(answerTo(request)).isEqualTo(403);
        assertThat(servedAs.get()).isNull();
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

        assertThat(response.getContentAsByteArray())
                .as("a hidden route reveals nothing in its body")
                .isEmpty();
        return response.getStatus();
    }

    private AgentJob runningJobOnAttempt(int attempt) {
        Workspace workspace = new Workspace();
        workspace.setId(7L);
        AgentJob job = new AgentJob();
        job.setId(UUID.randomUUID());
        job.setWorkspace(workspace);
        job.setStatus(AgentJobStatus.RUNNING);
        job.setWorkerId("test-worker");
        job.setRetryCount(attempt);
        job.setConfigSnapshot(new ConfigSnapshot(
                        ConfigSnapshot.SCHEMA_VERSION,
                        "openai-responses",
                        "https://api.example.com/v1",
                        "catalog-model",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        workspace.getId(),
                        600,
                        false,
                        null,
                        null,
                        null)
                .toJson(OBJECT_MAPPER));
        return job;
    }

    private static JobJwt jobJwt(AgentJob job, int attempt) {
        return jobJwt(job, attempt, "llm_proxy");
    }

    private static JobJwt jobJwt(AgentJob job, int attempt, String scope) {
        Instant now = Instant.now();
        return new JobJwt(
                job.getId(),
                job.getWorkspace().getId(),
                attempt,
                Set.of(scope),
                UUID.randomUUID().toString(),
                now,
                now.plusSeconds(60));
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
