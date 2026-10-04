package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.AuthRateLimitProperties;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.BucketResolver;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

class SandboxGatewayRateLimitFilterTest extends BaseUnitTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private ProxyBudgetGate budgetGate;

    @Mock
    private ProxyUsageAccumulator usageAccumulator;

    @Mock
    private MentorTurnUsageAccumulator mentorTurnUsageAccumulator;

    private final Map<String, Bucket> buckets = new HashMap<>();
    private final AtomicInteger served = new AtomicInteger();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldGiveEachAuthenticatedPrincipalItsOwnBudget() throws Exception {
        var filter = filterAllowing(1);
        authenticate("job:1");
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), this::serve);

        MockHttpServletResponse exhausted = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/internal/llm/responses"), exhausted, this::serve);

        authenticate("mentor-session:" + UUID.randomUUID());
        MockHttpServletResponse other = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), other, this::serve);

        assertThat(served).hasValue(2);
        assertThat(exhausted.getStatus()).isEqualTo(429);
        assertThat(other.getStatus()).isEqualTo(200);
    }

    /** A throttled sandbox has to be able to back off, so the refusal says when to come back. */
    @Test
    void shouldTellAThrottledSandboxWhenToRetry() throws Exception {
        var filter = filterAllowing(1);
        authenticate("job:1");
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), this::serve);

        MockHttpServletResponse refused = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/internal/llm/responses"), refused, this::serve);

        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getContentType()).isEqualTo("application/problem+json");
        assertThat(Long.parseLong(Objects.requireNonNull(refused.getHeader(HttpHeaders.RETRY_AFTER))))
                .isGreaterThanOrEqualTo(1);
        assertThat(refused.getContentAsString()).contains("Too many requests").contains("retryAfterSeconds");
    }

    /**
     * On a monolith the gateway shares the instance's Postgres-backed buckets, so a pool exhaustion or
     * a lock timeout reaches this filter as an exception. It must not become the sandbox's answer: the
     * call is served without a limit and the outage is counted.
     */
    @Test
    void shouldServeTheSandboxWhenTheBucketStoreIsUnreachable() throws Exception {
        var filter = filterResolving(
                (key, configuration) -> {
                    throw new IllegalStateException("bucket store unreachable");
                },
                1);
        authenticate("job:1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("POST", "/internal/llm/responses"), response, this::serve);

        assertThat(served).hasValue(1);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(meterRegistry
                        .get(AgentMetrics.SANDBOX_GATEWAY_LIMITER_ERRORS)
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    /** Authentication is the chain's decision, not the limiter's: it must not answer for it. */
    @Test
    void shouldPassAnUnauthenticatedRequestOnToTheAuthorizationRules() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filterAllowing(1).doFilter(new MockHttpServletRequest(), response, this::serve);

        assertThat(served).hasValue(1);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private SandboxGatewayRateLimitFilter filterAllowing(int requestsPerMinute) {
        return filterResolving(
                (key, configuration) -> buckets.computeIfAbsent(
                        key,
                        ignored -> Bucket.builder()
                                .addLimit(configuration.getBandwidths()[0])
                                .build()),
                requestsPerMinute);
    }

    private SandboxGatewayRateLimitFilter filterResolving(BucketResolver resolver, int requestsPerMinute) {
        return new SandboxGatewayRateLimitFilter(
                new AuthRateLimitProperties.Limit(requestsPerMinute, Duration.ofMinutes(1)),
                resolver,
                OBJECT_MAPPER,
                new ProxyAccounting(
                        budgetGate, usageAccumulator, mentorTurnUsageAccumulator, meterRegistry, OBJECT_MAPPER));
    }

    private void authenticate(String principalDescription) {
        var routing = new ProxyRouting(
                principalDescription, "openai-responses", "https://api.example.com/v1", null, null, null, null, null);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(routing, null));
    }

    private void serve(ServletRequest request, ServletResponse response) {
        served.incrementAndGet();
    }
}
