package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.ModelKind;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.agent.job.PrecomputeKindTotal;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.agent.usage.PricingState;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.JobJwt;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtInvalidException;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtVerifier;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

class JobTokenAuthenticationFilterTest extends BaseUnitTest {

    @Mock
    private AgentJobRepository agentJobRepository;

    @Mock
    private FilterChain filterChain;

    @Mock
    private WorkerJwtVerifier jwtVerifier;

    private MentorProxyCredentialRegistry mentorRegistry;
    private JobTokenAuthenticationFilter filter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String JOB_JWT = "job-jwt";

    @BeforeEach
    void setUp() {
        mentorRegistry = new MentorProxyCredentialRegistry();
        filter = new JobTokenAuthenticationFilter(
                agentJobRepository, jwtVerifier, mentorRegistry, objectMapper, "worker-1");
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    class IpValidation {

        @Test
        void shouldRejectNonPrivateIp() throws Exception {
            var request = new MockHttpServletRequest();
            request.setRemoteAddr("8.8.8.8");
            request.addHeader("Authorization", "Bearer " + JOB_JWT);
            var response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
            verify(filterChain, never()).doFilter(any(), any());
        }

        @Test
        void shouldAcceptLoopbackIp() {
            assertThat(JobTokenAuthenticationFilter.isPrivateIp("127.0.0.1")).isTrue();
        }

        @Test
        void shouldAcceptClassA() {
            assertThat(JobTokenAuthenticationFilter.isPrivateIp("10.0.0.5")).isTrue();
        }

        @Test
        @DisplayName("should accept 172.16.x.x")
        void shouldAcceptClassB() {
            assertThat(JobTokenAuthenticationFilter.isPrivateIp("172.18.0.2")).isTrue();
        }

        @Test
        @DisplayName("should accept 192.168.x.x")
        void shouldAcceptClassC() {
            assertThat(JobTokenAuthenticationFilter.isPrivateIp("192.168.1.1")).isTrue();
        }

        @Test
        void shouldRejectPublicIp() {
            assertThat(JobTokenAuthenticationFilter.isPrivateIp("1.2.3.4")).isFalse();
        }

        @Test
        void shouldRejectNullIp() {
            assertThat(JobTokenAuthenticationFilter.isPrivateIp(null)).isFalse();
        }

        @Test
        void shouldAcceptIpv6Loopback() {
            assertThat(JobTokenAuthenticationFilter.isPrivateIp("::1")).isTrue();
        }

        @Test
        void shouldAcceptIpv6LinkLocal() {
            assertThat(JobTokenAuthenticationFilter.isPrivateIp("fe80::1")).isTrue();
        }

        @Test
        void shouldRejectMalformedIp() {
            assertThat(JobTokenAuthenticationFilter.isPrivateIp("not-an-ip")).isFalse();
        }

        @Test
        void shouldRejectHostname() {
            // Hostnames must be rejected before InetAddress.getByName() to prevent DNS resolution
            assertThat(JobTokenAuthenticationFilter.isPrivateIp("localhost")).isFalse();
            assertThat(JobTokenAuthenticationFilter.isPrivateIp("internal.corp"))
                    .isFalse();
        }
    }

    @Nested
    class TokenValidation {

        @Test
        void shouldReturn401WhenNoAuthHeader() throws Exception {
            var request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.2");
            var response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            verify(filterChain, never()).doFilter(any(), any());
        }

        @Test
        void shouldRejectXApiKeyProviderHeader() throws Exception {
            var request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.2");
            request.addHeader("x-api-key", JOB_JWT);
            var response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            verify(filterChain, never()).doFilter(any(), any());
        }

        @Test
        void shouldExtractFromBearerAuth() throws Exception {
            var job = createRunningJob();
            when(jwtVerifier.verify(JOB_JWT)).thenReturn(jobJwt(job, Set.of("llm_proxy")));
            when(agentJobRepository.findByIdWithWorkspace(job.getId())).thenReturn(Optional.of(job));

            var authCapture = new AtomicReference<Authentication>();
            doAnswer(invocation -> {
                        authCapture.set(Objects.requireNonNull(
                                SecurityContextHolder.getContext().getAuthentication()));
                        return null;
                    })
                    .when(filterChain)
                    .doFilter(any(), any());

            var request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.2");
            request.addHeader("Authorization", "Bearer " + JOB_JWT);
            var response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
            verify(filterChain).doFilter(any(), any());
            assertThat(authCapture.get()).isInstanceOf(JobTokenAuthentication.class);
            ProxyRouting routing = ((JobTokenAuthentication) authCapture.get()).getPrincipal();
            assertThat(routing.principalDescription()).isEqualTo("job:" + job.getId());
            assertThat(Objects.requireNonNull(routing.attempt()).workerId()).isEqualTo("worker-1");
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        void shouldRejectApiKeyProviderHeader() throws Exception {
            var request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.2");
            request.addHeader("api-key", JOB_JWT);
            var response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            verify(filterChain, never()).doFilter(any(), any());
        }

        @Test
        void shouldReturn401ForMalformedToken() throws Exception {
            when(jwtVerifier.verify("not a valid base64!!!"))
                    .thenThrow(new WorkerJwtInvalidException("invalid token", "decode"));
            var request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.2");
            request.addHeader("Authorization", "Bearer not a valid base64!!!");
            var response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @Test
        void shouldReturn401WhenJobDoesNotExist() throws Exception {
            AgentJob job = createRunningJob();
            when(jwtVerifier.verify(JOB_JWT)).thenReturn(jobJwt(job, Set.of("llm_proxy")));
            when(agentJobRepository.findByIdWithWorkspace(job.getId())).thenReturn(Optional.empty());

            var request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.2");
            request.addHeader("Authorization", "Bearer " + JOB_JWT);
            var response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @Test
        void shouldReturn401WhenAttemptDoesNotMatch() throws Exception {
            AgentJob job = createRunningJob();
            JobJwt current = jobJwt(job, Set.of("llm_proxy"));
            JobJwt stale = new JobJwt(
                    current.jobId(),
                    current.workspaceId(),
                    current.attempt() + 1,
                    current.scopes(),
                    current.jti(),
                    current.issuedAt(),
                    current.expiresAt());
            when(jwtVerifier.verify(JOB_JWT)).thenReturn(stale);
            when(agentJobRepository.findByIdWithWorkspace(job.getId())).thenReturn(Optional.of(job));

            assertThat(authenticate(JOB_JWT).getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @ParameterizedTest
        @ValueSource(strings = {"worker-2", ""})
        void shouldRejectJobWhenGatewayDoesNotOwnIt(String owner) throws Exception {
            AgentJob job = createRunningJob();
            job.setWorkerId(owner.isEmpty() ? null : owner);
            when(jwtVerifier.verify(JOB_JWT)).thenReturn(jobJwt(job, Set.of("llm_proxy")));
            when(agentJobRepository.findByIdWithWorkspace(job.getId())).thenReturn(Optional.of(job));

            assertThat(authenticate(JOB_JWT).getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            verify(filterChain, never()).doFilter(any(), any());
        }

        @Test
        void shouldReturn409ForResultUploadOnAnotherWorker() throws Exception {
            AgentJob job = createRunningJob();
            job.setWorkerId("worker-2");
            when(jwtVerifier.verify(JOB_JWT)).thenReturn(jobJwt(job, Set.of("llm_proxy")));
            when(agentJobRepository.findByIdWithWorkspace(job.getId())).thenReturn(Optional.of(job));

            var request = new MockHttpServletRequest("POST", "/internal/llm/runtime/" + job.getId() + "/result");
            request.setRemoteAddr("10.0.0.2");
            request.addHeader("Authorization", "Bearer " + JOB_JWT);
            var response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_CONFLICT);
            verify(filterChain, never()).doFilter(any(), any());
        }

        @Test
        void shouldRejectCancelledResultUploadWithoutRevealingOwnership() throws Exception {
            AgentJob job = createRunningJob();
            job.setStatus(AgentJobStatus.CANCELLED);
            job.setWorkerId("worker-2");
            when(jwtVerifier.verify(JOB_JWT)).thenReturn(jobJwt(job, Set.of("llm_proxy")));
            when(agentJobRepository.findByIdWithWorkspace(job.getId())).thenReturn(Optional.of(job));

            var request = new MockHttpServletRequest("POST", "/internal/llm/runtime/" + job.getId() + "/result");
            request.setRemoteAddr("10.0.0.2");
            request.addHeader("Authorization", "Bearer " + JOB_JWT);
            var response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @Test
        void shouldRejectResultUploadForAnotherJob() throws Exception {
            AgentJob job = createRunningJob();
            when(jwtVerifier.verify(JOB_JWT)).thenReturn(jobJwt(job, Set.of("llm_proxy")));

            var request = new MockHttpServletRequest("POST", "/internal/llm/runtime/" + UUID.randomUUID() + "/result");
            request.setRemoteAddr("10.0.0.2");
            request.addHeader("Authorization", "Bearer " + JOB_JWT);
            var response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        }

        private MockHttpServletResponse authenticate(String token) throws Exception {
            var request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.2");
            request.addHeader("Authorization", "Bearer " + token);
            var response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, filterChain);
            return response;
        }

        @Test
        void shouldReturn401WhenJobHasNoConfigSnapshot() throws Exception {
            var job = new AgentJob();
            job.setId(UUID.randomUUID());
            Workspace workspace = new Workspace();
            workspace.setId(7L);
            job.setWorkspace(workspace);
            job.setStatus(AgentJobStatus.RUNNING);
            job.setWorkerId("worker-1");
            when(jwtVerifier.verify(JOB_JWT)).thenReturn(jobJwt(job, Set.of("llm_proxy")));
            when(agentJobRepository.findByIdWithWorkspace(job.getId())).thenReturn(Optional.of(job));

            var request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.2");
            request.addHeader("Authorization", "Bearer " + JOB_JWT);
            var response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            verify(filterChain, never()).doFilter(any(), any());
        }

        @ParameterizedTest(name = "Authorization: [{0}]")
        @ValueSource(strings = {"Bearer ", "Bearer    "})
        void shouldReturn401ForABearerHeaderWithNoToken(String header) throws Exception {
            var request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.2");
            request.addHeader("Authorization", header);
            var response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            verify(filterChain, never()).doFilter(any(), any());
        }

        @Test
        void shouldAcceptCaseInsensitiveBearer() throws Exception {
            var job = createRunningJob();

            Authentication installed = authenticateAndCaptureAuthentication(job, "bearer " + JOB_JWT, null);

            assertAuthenticatedAsJob(installed, job);
        }

        @Test
        void shouldIgnoreProviderKeyWhenBearerIsPresent() throws Exception {
            var job = createRunningJob();

            Authentication installed =
                    authenticateAndCaptureAuthentication(job, "Bearer " + JOB_JWT, "provider-key-must-be-ignored");

            assertAuthenticatedAsJob(installed, job);
        }

        private void assertAuthenticatedAsJob(Authentication installed, AgentJob job) {
            assertThat(installed).isInstanceOf(JobTokenAuthentication.class);
            ProxyRouting routing = ((JobTokenAuthentication) installed).getPrincipal();
            assertThat(routing.principalDescription()).isEqualTo("job:" + job.getId());
            assertThat(Objects.requireNonNull(routing.attempt()).workerId()).isEqualTo("worker-1");
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        private Authentication authenticateAndCaptureAuthentication(
                AgentJob job, String authorizationHeader, @Nullable String providerKeyHeader) throws Exception {
            when(jwtVerifier.verify(JOB_JWT)).thenReturn(jobJwt(job, Set.of("llm_proxy")));
            when(agentJobRepository.findByIdWithWorkspace(job.getId())).thenReturn(Optional.of(job));
            var authCapture = new AtomicReference<Authentication>();
            doAnswer(invocation -> {
                        authCapture.set(Objects.requireNonNull(
                                SecurityContextHolder.getContext().getAuthentication()));
                        return null;
                    })
                    .when(filterChain)
                    .doFilter(any(), any());

            var request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.2");
            if (providerKeyHeader != null) {
                request.addHeader("x-api-key", providerKeyHeader);
            }
            request.addHeader("Authorization", authorizationHeader);

            filter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);
            Authentication authentication = authCapture.get();
            assertThat(authentication).isNotNull();
            return authentication;
        }

        @Test
        void shouldClearContextOnFilterChainException() throws Exception {
            var job = createRunningJob();
            when(jwtVerifier.verify(JOB_JWT)).thenReturn(jobJwt(job, Set.of("llm_proxy")));
            when(agentJobRepository.findByIdWithWorkspace(job.getId())).thenReturn(Optional.of(job));
            doAnswer(invocation -> {
                        throw new ServletException("Simulated failure");
                    })
                    .when(filterChain)
                    .doFilter(any(), any());

            var request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.2");
            request.addHeader("Authorization", "Bearer " + JOB_JWT);
            var response = new MockHttpServletResponse();

            assertThatThrownBy(() -> filter.doFilterInternal(request, response, filterChain))
                    .isInstanceOf(ServletException.class);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        void shouldNotBeBypassedByXForwardedFor() throws Exception {
            var request = new MockHttpServletRequest();
            request.setRemoteAddr("8.8.8.8");
            request.addHeader("X-Forwarded-For", "10.0.0.2");
            request.addHeader("Authorization", "Bearer " + JOB_JWT);
            var response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
            verify(filterChain, never()).doFilter(any(), any());
        }
    }

    @Nested
    @DisplayName("Mentor credentials")
    class MentorTokenFallback {

        @Test
        void authenticatesValidMentorCredential() throws Exception {
            String mentorToken = mentorRegistry.mint(
                    UUID.randomUUID(),
                    new MentorProxyCredentialRegistry.Route(
                            "openai-completions", "https://api.openai.com", null, null, null, null));

            var authCapture = new AtomicReference<Authentication>();
            doAnswer(invocation -> {
                        authCapture.set(Objects.requireNonNull(
                                SecurityContextHolder.getContext().getAuthentication()));
                        return null;
                    })
                    .when(filterChain)
                    .doFilter(any(), any());

            var request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.2");
            request.addHeader("Authorization", "Bearer " + mentorToken);
            var response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
            Authentication authentication = authCapture.get();
            assertThat(authentication).isNotNull();
            assertInstanceOf(JobTokenAuthentication.class, authentication);
            ProxyRouting routing = ((JobTokenAuthentication) authentication).getPrincipal();
            assertThat(routing.apiProtocol()).isEqualTo("openai-completions");
        }

        @Test
        void aTokenMatchingNeitherJobNorMentorRegistryIsRefused() throws Exception {
            when(jwtVerifier.verify(JOB_JWT)).thenThrow(new WorkerJwtInvalidException("invalid token", "decode"));

            var request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.2");
            request.addHeader("Authorization", "Bearer " + JOB_JWT);
            var response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            verify(filterChain, never()).doFilter(any(), any());
        }
    }

    @Nested
    @DisplayName("Billed attempt")
    class BilledAttempt {

        @Test
        void theRoutingCarriesTheAttemptNumber() throws Exception {
            var job = createRunningJob();
            job.setRetryCount(3);

            ProxyRouting routing = authenticateAndCaptureRouting(job);

            assertThat(routing.attempt()).isNotNull();
            assertThat(routing.attempt().number()).isEqualTo(3);
            assertThat(routing.attempt().sourceId()).isEqualTo(job.getId());
        }

        @Test
        void theRoutingPricesWhatTheAttemptHasAlreadySpent() throws Exception {
            var job = createRunningJob(new LlmPriceSnapshot(
                    FundingSource.INSTANCE,
                    PricingState.PRICED,
                    1L,
                    null,
                    new BigDecimal("10"),
                    new BigDecimal("30"),
                    new BigDecimal("1"),
                    new BigDecimal("2")));
            job.setLlmTotalInputTokens(1_000_000);
            job.setLlmTotalOutputTokens(100_000);

            ProxyRouting routing = authenticateAndCaptureRouting(job);

            assertThat(routing.inFlightSpendUsd()).isEqualByComparingTo("13");
        }

        @Test
        void aSnapshotWithoutAPriceContributesNothing() throws Exception {
            var job = createRunningJob();
            job.setLlmTotalInputTokens(1_000_000);

            ProxyRouting routing = authenticateAndCaptureRouting(job);

            assertThat(routing.inFlightSpendUsd()).isEqualByComparingTo("0");
        }

        private ProxyRouting authenticateAndCaptureRouting(AgentJob job) throws Exception {
            when(jwtVerifier.verify(JOB_JWT)).thenReturn(jobJwt(job, Set.of("llm_proxy")));
            when(agentJobRepository.findByIdWithWorkspace(job.getId())).thenReturn(Optional.of(job));
            var authCapture = new AtomicReference<Authentication>();
            doAnswer(invocation -> {
                        authCapture.set(Objects.requireNonNull(
                                SecurityContextHolder.getContext().getAuthentication()));
                        return null;
                    })
                    .when(filterChain)
                    .doFilter(any(), any());

            var request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.2");
            request.addHeader("Authorization", "Bearer " + JOB_JWT);
            filter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);

            Authentication authentication = authCapture.get();
            assertThat(authentication).isNotNull();
            assertInstanceOf(JobTokenAuthentication.class, authentication);
            return ((JobTokenAuthentication) authentication).getPrincipal();
        }
    }

    /** Which scope reaches which path is the chain's rule, in {@link LlmProxySecurityConfigTest}. */
    @Nested
    class PrecomputeRouting {

        private static final LlmPriceSnapshot DECISION_PRICE = new LlmPriceSnapshot(
                FundingSource.WORKSPACE,
                PricingState.PRICED,
                null,
                4L,
                new BigDecimal("2"),
                new BigDecimal("8"),
                null,
                null);

        @Test
        void shouldRouteTheChatSlotToTheReviewModelWhenThePrecomputeTokenCallsIt() throws Exception {
            AgentJob job = createPrecomputeJob();

            ProxyRouting routing = routeAs(job, "/internal/llm/precompute/chat/chat/completions");

            assertThat(routing.principalDescription()).isEqualTo("job:" + job.getId() + ":precompute");
            assertThat(routing.apiProtocol()).isEqualTo("openai-completions");
            assertThat(routing.baseUrl()).isEqualTo("https://api.openai.com/v1");
            assertThat(routing.precomputeSlot()).isEqualTo(ModelKind.CHAT);
            assertThat(Objects.requireNonNull(routing.attempt()).sourceType()).isEqualTo(LlmUsageSourceType.AGENT_JOB);
        }

        @ParameterizedTest
        @CsvSource({
            "decision,decisions,DECISION,openai-decisions,PRECOMPUTE_DECISION,31",
            "embedding,embeddings,EMBEDDING,openai-embeddings,PRECOMPUTE_EMBEDDING,32",
            "reranking,rerank,RERANKING,cohere-rerank,PRECOMPUTE_RERANKING,33"
        })
        void shouldRouteASlotToItsFrozenModelWhenThePrecomputeTokenCallsIt(
                String slot,
                String op,
                ModelKind kind,
                String protocol,
                LlmUsageSourceType sourceType,
                long connectionId)
                throws Exception {
            AgentJob job = createPrecomputeJob();

            ProxyRouting routing = routeAs(job, "/internal/llm/precompute/" + slot + "/" + op);

            assertThat(routing.principalDescription()).isEqualTo("job:" + job.getId() + ":precompute");
            assertThat(routing.precomputeSlot()).isEqualTo(kind);
            assertThat(routing.apiProtocol()).isEqualTo(protocol);
            assertThat(routing.baseUrl()).isEqualTo("https://models.example.com/" + slot);
            assertThat(routing.connectionScope()).isEqualTo(FundingSource.WORKSPACE);
            assertThat(routing.connectionId()).isEqualTo(connectionId);
            ProxyRouting.BilledAttempt attempt = Objects.requireNonNull(routing.attempt());
            assertThat(attempt.sourceType()).isEqualTo(sourceType);
            assertThat(attempt.sourceId()).isEqualTo(job.getId());
        }

        @Test
        void shouldRefuseASlotWhenNoModelOfItsKindIsBound() throws Exception {
            AgentJob job = createRunningJob();
            when(jwtVerifier.verify(JOB_JWT)).thenReturn(jobJwt(job, Set.of("llm_precompute")));
            when(agentJobRepository.findByIdWithWorkspace(job.getId())).thenReturn(Optional.of(job));

            var response = call("POST", "/internal/llm/precompute/decision/decisions");

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
            assertThat(response.getErrorMessage()).isEqualTo("No decision model is bound for this job");
            verify(filterChain, never()).doFilter(any(), any());
        }

        @Test
        void shouldRefuseThePrecomputeTokenWhenItsJobHasEnded() throws Exception {
            AgentJob job = createPrecomputeJob();
            job.setStatus(AgentJobStatus.COMPLETED);
            when(jwtVerifier.verify(JOB_JWT)).thenReturn(jobJwt(job, Set.of("llm_precompute")));
            when(agentJobRepository.findByIdWithWorkspace(job.getId())).thenReturn(Optional.of(job));

            var response = call("POST", "/internal/llm/precompute/decision/decisions");

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            verify(filterChain, never()).doFilter(any(), any());
        }

        @ParameterizedTest
        @ValueSource(strings = {"/internal/llm/precompute/speech/speak", "/internal/llm/precompute/decision"})
        void shouldRefuseAPrecomputePathWhenItNamesNoKnownSlot(String path) throws Exception {
            AgentJob job = createPrecomputeJob();
            when(jwtVerifier.verify(JOB_JWT)).thenReturn(jobJwt(job, Set.of("llm_precompute")));
            when(agentJobRepository.findByIdWithWorkspace(job.getId())).thenReturn(Optional.of(job));

            assertThat(call("POST", path).getStatus()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
            verify(filterChain, never()).doFilter(any(), any());
        }

        @Test
        void shouldPriceTheAttemptsPrecomputeUsageFromTheSamePurseWhenRoutingASlot() throws Exception {
            AgentJob job = createPrecomputeJob();
            when(agentJobRepository.sumPrecomputeUsageByKind(7L, job.getId(), 0))
                    .thenReturn(List.of(new PrecomputeKindTotal(ModelKind.DECISION, 4, 1_000_000, 100_000)));

            ProxyRouting routing = routeAs(job, "/internal/llm/precompute/embedding/embeddings");

            // 1M input at $2 plus 100k output at $8. The review model is paid from the other purse.
            assertThat(routing.inFlightSpendUsd()).isEqualByComparingTo("2.8");
            // The per-attempt cap reads the same totals, whichever purse paid for them.
            assertThat(Objects.requireNonNull(routing.attempt()).precomputeTokens())
                    .isEqualTo(1_100_000);
        }

        private ProxyRouting routeAs(AgentJob job, String path) throws Exception {
            when(jwtVerifier.verify(JOB_JWT)).thenReturn(jobJwt(job, Set.of("llm_precompute")));
            when(agentJobRepository.findByIdWithWorkspace(job.getId())).thenReturn(Optional.of(job));
            var authCapture = new AtomicReference<Authentication>();
            doAnswer(invocation -> {
                        authCapture.set(Objects.requireNonNull(
                                SecurityContextHolder.getContext().getAuthentication()));
                        return null;
                    })
                    .when(filterChain)
                    .doFilter(any(), any());

            assertThat(call("POST", path).getStatus()).isEqualTo(HttpServletResponse.SC_OK);
            Authentication authentication = authCapture.get();
            assertInstanceOf(JobTokenAuthentication.class, authentication);
            return ((JobTokenAuthentication) authentication).getPrincipal();
        }

        private MockHttpServletResponse call(String method, String path) throws Exception {
            var request = new MockHttpServletRequest(method, path);
            request.setRemoteAddr("10.0.0.2");
            request.addHeader("Authorization", "Bearer " + JOB_JWT);
            var response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, filterChain);
            return response;
        }

        private AgentJob createPrecomputeJob() {
            AgentJob job = createRunningJob();
            Map<ModelKind, FrozenModel> slots = new EnumMap<>(ModelKind.class);
            slots.put(ModelKind.DECISION, slot(ModelKind.DECISION, "openai-decisions", 31L, DECISION_PRICE));
            slots.put(ModelKind.EMBEDDING, slot(ModelKind.EMBEDDING, "openai-embeddings", 32L, null));
            slots.put(ModelKind.RERANKING, slot(ModelKind.RERANKING, "cohere-rerank", 33L, null));
            job.setConfigSnapshot(ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper)
                    .withPrecompute(slots)
                    .toJson(objectMapper));
            return job;
        }

        private static FrozenModel slot(
                ModelKind kind, String protocol, long connectionId, @Nullable LlmPriceSnapshot price) {
            return new FrozenModel(
                    protocol,
                    "https://models.example.com/" + kind.name().toLowerCase(Locale.ROOT),
                    kind.name().toLowerCase(Locale.ROOT) + "-model",
                    FundingSource.WORKSPACE,
                    connectionId,
                    connectionId + 10,
                    7L,
                    null,
                    null,
                    price);
        }
    }

    private AgentJob createRunningJob() {
        return createRunningJob(null);
    }

    private AgentJob createRunningJob(@Nullable LlmPriceSnapshot price) {
        var job = new AgentJob();
        ConfigSnapshot snapshot = new ConfigSnapshot(
                ConfigSnapshot.SCHEMA_VERSION,
                "openai-completions",
                "https://api.openai.com/v1",
                "gpt-5-mini",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                600,
                false,
                price,
                null,
                null);
        job.setConfigSnapshot(snapshot.toJson(objectMapper));
        job.setId(UUID.randomUUID());
        Workspace workspace = new Workspace();
        workspace.setId(7L);
        job.setWorkspace(workspace);
        job.setStatus(AgentJobStatus.RUNNING);
        job.setWorkerId("worker-1");
        return job;
    }

    @Nested
    class NoAmbientCredentialIsAccepted {

        @Test
        void theSameTokenInACookieAuthenticatesNobody() throws Exception {
            var request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.2");
            request.setCookies(new Cookie("hephaestus_session", JOB_JWT), new Cookie("JSESSIONID", JOB_JWT));

            filter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            verify(jwtVerifier, never()).verify(any());
            verify(filterChain, never()).doFilter(any(), any());
        }

        @Test
        void theSameTokenInAQueryParameterAuthenticatesNobody() throws Exception {
            var request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.2");
            request.setParameter("access_token", JOB_JWT);
            request.setParameter("token", JOB_JWT);

            filter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            verify(jwtVerifier, never()).verify(any());
            verify(filterChain, never()).doFilter(any(), any());
        }

        @Test
        void theSameTokenInTheHeaderDoesAuthenticate() throws Exception {
            AgentJob job = createRunningJob();
            when(jwtVerifier.verify(JOB_JWT)).thenReturn(jobJwt(job, Set.of("llm_proxy")));
            when(agentJobRepository.findByIdWithWorkspace(job.getId())).thenReturn(Optional.of(job));
            var authInsideChain = new AtomicReference<Authentication>();
            doAnswer(invocation -> {
                        authInsideChain.set(Objects.requireNonNull(
                                SecurityContextHolder.getContext().getAuthentication()));
                        return null;
                    })
                    .when(filterChain)
                    .doFilter(any(), any());
            var request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.2");
            request.addHeader("Authorization", "Bearer " + JOB_JWT);

            filter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);

            assertThat(authInsideChain.get()).isNotNull();
        }
    }

    private static JobJwt jobJwt(AgentJob job, Set<String> scopes) {
        Instant now = Instant.now();
        return new JobJwt(
                job.getId(),
                job.getWorkspace().getId(),
                job.getRetryCount(),
                scopes,
                UUID.randomUUID().toString(),
                now,
                now.plusSeconds(60));
    }
}
