package de.tum.cit.aet.hephaestus.core.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.core.auth.metrics.AuthMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for the rate-limit filter's endpoint matching, key derivation, and 429 contract.
 *
 * <p>Uses an in-JVM {@link BucketResolver} (a {@code ConcurrentHashMap} of local buckets) so the
 * key/limit logic is exercised without a database — the same seam the production code wires to a
 * Postgres ProxyManager.
 */
class AuthRateLimitFilterTest extends BaseUnitTest {

    private final Map<String, Bucket> store = new ConcurrentHashMap<>();
    private final Map<String, BucketConfiguration> capturedConfigs = new ConcurrentHashMap<>();

    private final BucketResolver resolver = (key, config) -> {
        capturedConfigs.put(key, config);
        return store.computeIfAbsent(
                key, k -> Bucket.builder().addLimit(config.getBandwidths()[0]).build());
    };

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AuthMetrics metrics = new AuthMetrics(meterRegistry);

    private AuthRateLimitFilter filter(AuthRateLimitProperties props) {
        return new AuthRateLimitFilter(props, resolver, objectMapper, metrics);
    }

    private double blockedCount(String bucket) {
        var counter = meterRegistry
                .find("auth.ratelimit.blocked")
                .tag("bucket", bucket)
                .counter();
        return counter == null ? 0d : counter.count();
    }

    private static AuthRateLimitProperties props(AuthRateLimitProperties.Limit... overrides) {
        // Defaults from the spec; tests override the oauth-authz limit via the first vararg.
        return new AuthRateLimitProperties(
                true,
                overrides.length > 0 ? overrides[0] : new AuthRateLimitProperties.Limit(20, Duration.ofMinutes(1)),
                new AuthRateLimitProperties.Limit(60, Duration.ofMinutes(1)),
                new AuthRateLimitProperties.Limit(10, Duration.ofMinutes(1)),
                new AuthRateLimitProperties.Limit(3, Duration.ofHours(1)),
                new AuthRateLimitProperties.Limit(10, Duration.ofHours(1)),
                new AuthRateLimitProperties.Limit(20, Duration.ofMinutes(10)),
                new AuthRateLimitProperties.Limit(10, Duration.ofHours(1)),
                new AuthRateLimitProperties.Limit(10, Duration.ofHours(1)));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String subject) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "ES256")
                .subject(subject)
                .claim("roles", List.of("app_admin"))
                .build();
        AbstractAuthenticationToken auth = new JwtAuthenticationToken(jwt);
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void nonMatchingPathPassesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/workspaces");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(props()).doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        assertThat(store).isEmpty();
    }

    @Test
    void disabledFilterPassesThroughWithoutTouchingBuckets() throws Exception {
        AuthRateLimitProperties disabled = new AuthRateLimitProperties(
                false,
                new AuthRateLimitProperties.Limit(1, Duration.ofMinutes(1)),
                new AuthRateLimitProperties.Limit(1, Duration.ofMinutes(1)),
                new AuthRateLimitProperties.Limit(1, Duration.ofMinutes(1)),
                new AuthRateLimitProperties.Limit(1, Duration.ofHours(1)),
                new AuthRateLimitProperties.Limit(1, Duration.ofHours(1)),
                new AuthRateLimitProperties.Limit(1, Duration.ofHours(1)),
                new AuthRateLimitProperties.Limit(1, Duration.ofHours(1)),
                new AuthRateLimitProperties.Limit(1, Duration.ofHours(1)));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/refresh");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(disabled).doFilter(request, response, chain);
        filter(disabled).doFilter(request, response, chain);

        verify(chain, times(2)).doFilter(request, response);
        assertThat(store).isEmpty();
    }

    @Test
    void oauthAuthorizationIsKeyedByClientIpAndCapped() throws Exception {
        // capacity 2 to keep the test fast
        AuthRateLimitProperties p = props(new AuthRateLimitProperties.Limit(2, Duration.ofMinutes(1)));
        AuthRateLimitFilter f = filter(p);

        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/oauth2/authorization/github");
            req.setRemoteAddr("203.0.113.7");
            MockHttpServletResponse res = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);
            f.doFilter(req, res, chain);
            verify(chain, times(1)).doFilter(req, res);
            assertThat(res.getStatus()).isEqualTo(HttpStatus.OK.value());
        }

        // third request from the same IP is rejected
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/oauth2/authorization/github");
        req.setRemoteAddr("203.0.113.7");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        f.doFilter(req, res, chain);

        verify(chain, never()).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(res.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(res.getHeader(HttpHeaders.RETRY_AFTER)).isNotNull();
        assertThat(Long.parseLong(res.getHeader(HttpHeaders.RETRY_AFTER))).isGreaterThanOrEqualTo(1);
        assertThat(res.getContentAsString()).contains("Too Many Requests").contains("retryAfterSeconds");
        assertThat(store).containsOnlyKeys("oauth-authz:ip:203.0.113.7");
        // the 429 path increments the rate-limit metric, tagged by the bucket namespace
        assertThat(blockedCount("oauth-authz")).isEqualTo(1d);
        assertThat(blockedCount("refresh")).isEqualTo(0d);
    }

    @Test
    void differentIpsGetIndependentOauthBuckets() throws Exception {
        AuthRateLimitProperties p = props(new AuthRateLimitProperties.Limit(1, Duration.ofMinutes(1)));
        AuthRateLimitFilter f = filter(p);

        MockHttpServletRequest a = new MockHttpServletRequest("GET", "/oauth2/authorization/github");
        a.setRemoteAddr("198.51.100.1");
        MockHttpServletResponse ra = new MockHttpServletResponse();
        FilterChain ca = mock(FilterChain.class);
        f.doFilter(a, ra, ca);

        MockHttpServletRequest b = new MockHttpServletRequest("GET", "/oauth2/authorization/github");
        b.setRemoteAddr("198.51.100.2");
        MockHttpServletResponse rb = new MockHttpServletResponse();
        FilterChain cb = mock(FilterChain.class);
        f.doFilter(b, rb, cb);

        verify(ca, times(1)).doFilter(a, ra);
        verify(cb, times(1)).doFilter(b, rb);
        assertThat(store).containsOnlyKeys("oauth-authz:ip:198.51.100.1", "oauth-authz:ip:198.51.100.2");
    }

    @Test
    void refreshIsKeyedByAccountSubjectWhenAuthenticated() throws Exception {
        authenticateAs("4242");
        AuthRateLimitFilter f = filter(props());

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/auth/refresh");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        f.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        assertThat(store).containsOnlyKeys("refresh:acct:4242");
        // verify the configured limit (60/min) reached the resolver for this endpoint
        var config = capturedConfigs.get("refresh:acct:4242");
        org.junit.jupiter.api.Assertions.assertNotNull(config);
        assertThat(config.getBandwidths()[0].getCapacity()).isEqualTo(60);
    }

    @Test
    void refreshFallsBackToIpWhenUnauthenticated() throws Exception {
        AuthRateLimitFilter f = filter(props());

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/auth/refresh");
        req.setRemoteAddr("192.0.2.55");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        f.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        assertThat(store).containsOnlyKeys("refresh:ip:192.0.2.55");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/workspaces/demo/mentor/chat",
                "/workspaces/demo/practices/review-requests",
                "/workspaces/demo/connections/7/sync/jobs"
            })
    void expensiveEndpointsUseAccountBuckets(String path) throws Exception {
        authenticateAs("42");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(props()).doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(store.keySet()).singleElement().asString().endsWith(":acct:42");
    }

    @Test
    void shouldKeyEveryUserViewReadOnTheOperatorWhenTheyBrowseSeveralUsersAndWorkspaces() throws Exception {
        authenticateAs("99");
        AuthRateLimitFilter f = filter(props());

        f.doFilter(
                new MockHttpServletRequest("GET", "/workspaces/example/user-view/users/1/practices"),
                new MockHttpServletResponse(),
                mock(FilterChain.class));
        f.doFilter(
                new MockHttpServletRequest("GET", "/workspaces/other/user-view/users/2/conversations"),
                new MockHttpServletResponse(),
                mock(FilterChain.class));
        f.doFilter(new MockHttpServletRequest("GET", "/user"), new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(store).containsOnlyKeys("user-view:acct:99");
    }

    @Test
    void deleteUserUsesThreePerHourBudgetKeyedByAccount() throws Exception {
        authenticateAs("7");
        // use the spec default (3/hour) — exhaust then assert 429
        AuthRateLimitFilter f = filter(props());

        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);
            f.doFilter(new MockHttpServletRequest("DELETE", "/user"), res, chain);
            assertThat(res.getStatus()).isEqualTo(HttpStatus.OK.value());
        }

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        FilterChain blockedChain = mock(FilterChain.class);
        f.doFilter(new MockHttpServletRequest("DELETE", "/user"), blocked, blockedChain);

        verify(blockedChain, never()).doFilter(ArgumentMatchers.any(), ArgumentMatchers.any());
        assertThat(blocked.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(store).containsOnlyKeys("delete-user:acct:7");
        var config = capturedConfigs.get("delete-user:acct:7");
        org.junit.jupiter.api.Assertions.assertNotNull(config);
        assertThat(config.getBandwidths()[0].getCapacity()).isEqualTo(3);
    }

    @Test
    void workerAndWebhookPathsAreNeverRateLimited() throws Exception {
        AuthRateLimitFilter f = filter(props());

        for (String path : new String[] {"/api/workers/exchange", "/webhooks/gitlab", "/actuator/health"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
            MockHttpServletResponse res = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);
            f.doFilter(req, res, chain);
            verify(chain, times(1)).doFilter(req, res);
        }
        assertThat(store).isEmpty();
    }

    @Test
    void xffIsIgnoredClientIpAlwaysFromRemoteAddr() throws Exception {
        // The filter never parses X-Forwarded-For: under forward-headers-strategy=native the
        // RemoteIpValve validates the proxy chain upstream and rewrites getRemoteAddr() to the real
        // client. So neither a single spoofed XFF value nor a multi-hop "evil1, evil2, fake" chain
        // can influence the bucket key — getRemoteAddr() is authoritative.
        AuthRateLimitFilter f = filter(props());

        MockHttpServletRequest single = new MockHttpServletRequest("GET", "/oauth2/authorization/github");
        single.addHeader("X-Forwarded-For", "203.0.113.7");
        single.setRemoteAddr("10.0.0.1");
        f.doFilter(single, new MockHttpServletResponse(), mock(FilterChain.class));

        MockHttpServletRequest multiHop = new MockHttpServletRequest("GET", "/oauth2/authorization/github");
        multiHop.addHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2, 203.0.113.50");
        multiHop.setRemoteAddr("10.0.0.2");
        f.doFilter(multiHop, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(store).containsOnlyKeys("oauth-authz:ip:10.0.0.1", "oauth-authz:ip:10.0.0.2");
    }

    @Test
    void spoofedXffCannotMintUnlimitedBucketsAcrossRequests() throws Exception {
        // capacity 1: an attacker rotating the XFF value across requests must still collapse into the
        // SAME bucket (keyed by the unforgeable remote address) and get 429 on the second attempt.
        AuthRateLimitFilter f = filter(props(new AuthRateLimitProperties.Limit(1, Duration.ofMinutes(1))));

        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/oauth2/authorization/github");
        first.addHeader("X-Forwarded-For", "9.9.9.1, 203.0.113.50");
        first.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse firstRes = new MockHttpServletResponse();
        FilterChain firstChain = mock(FilterChain.class);
        f.doFilter(first, firstRes, firstChain);
        verify(firstChain, times(1)).doFilter(first, firstRes);

        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/oauth2/authorization/github");
        second.addHeader("X-Forwarded-For", "9.9.9.2, 203.0.113.50"); // rotated spoof, same socket peer
        second.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse secondRes = new MockHttpServletResponse();
        FilterChain secondChain = mock(FilterChain.class);
        f.doFilter(second, secondRes, secondChain);

        verify(secondChain, never()).doFilter(second, secondRes);
        assertThat(secondRes.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(store).containsOnlyKeys("oauth-authz:ip:10.0.0.1");
    }

    @Test
    void failsOpenAndRecordsMetricWhenBucketBackendThrows() throws Exception {
        BucketResolver throwing = (key, config) -> {
            throw new RuntimeException("bucket store down");
        };
        AuthMetrics mockMetrics = mock(AuthMetrics.class);
        AuthRateLimitFilter f = new AuthRateLimitFilter(props(), throwing, objectMapper, mockMetrics);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/oauth2/authorization/github");
        req.setRemoteAddr("203.0.113.7");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        f.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(mockMetrics, times(1)).recordRateLimitBackendError();
    }

    @Test
    void costlyRequestFailsClosedWhenBucketBackendThrows() throws Exception {
        authenticateAs("42");
        BucketResolver throwing = (key, config) -> {
            throw new RuntimeException("bucket store down");
        };
        AuthRateLimitFilter f = new AuthRateLimitFilter(props(), throwing, objectMapper, metrics);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/workspaces/demo/mentor/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    }

    @Test
    void exportPostIsAccountKeyedAndDownloadIsNotLimited() throws Exception {
        authenticateAs("acct-42");
        AuthRateLimitFilter f = filter(props());

        f.doFilter(
                new MockHttpServletRequest("POST", "/user/exports"),
                new MockHttpServletResponse(),
                mock(FilterChain.class));
        assertThat(store).hasSize(1);
        assertThat(store.keySet().iterator().next()).contains("acct-42");

        store.clear();
        MockHttpServletRequest dl = new MockHttpServletRequest("GET", "/user/exports/abc/download");
        MockHttpServletResponse dlRes = new MockHttpServletResponse();
        FilterChain dlChain = mock(FilterChain.class);
        f.doFilter(dl, dlRes, dlChain);
        verify(dlChain, times(1)).doFilter(dl, dlRes);
        assertThat(store).isEmpty();
    }
}
