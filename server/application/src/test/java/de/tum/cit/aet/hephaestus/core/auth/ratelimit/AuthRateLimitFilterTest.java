package de.tum.cit.aet.hephaestus.core.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.core.auth.metrics.AuthMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
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

    // Bucket-key principals that must never reach a log line.
    private static final String ACCOUNT_CANARY = "8675309";
    private static final String IP_CANARY = "198.51.100.73";

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

    @Test
    void shouldShareThePublicActivityLimitAcrossWorkspaceSlugsButNotClientIps() throws Exception {
        var limited = filter(props());
        for (int i = 0; i < 60; i++) {
            var request = new MockHttpServletRequest("GET", "/public/workspaces/alpha/activity");
            request.setRemoteAddr("192.0.2.1");
            limited.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        }
        var request = new MockHttpServletRequest("GET", "/public/workspaces/bravo/activity/people/developer");
        request.setRemoteAddr("192.0.2.1");
        var blocked = new MockHttpServletResponse();
        limited.doFilter(request, blocked, mock(FilterChain.class));
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotBlank();
        assertThat(blocked.getContentType()).startsWith("application/problem+json");
        request.setRemoteAddr("192.0.2.2");
        var independent = new MockHttpServletResponse();
        limited.doFilter(request, independent, mock(FilterChain.class));
        assertThat(independent.getStatus()).isEqualTo(200);
    }

    @Test
    void shouldUseTheConfiguredAnonymousPublicBudgetForGetAndHead() throws Exception {
        var defaults = props();
        var configured = new AuthRateLimitProperties(
                true,
                defaults.oauthAuthorization(),
                defaults.refresh(),
                defaults.clientSession(),
                defaults.userView(),
                defaults.deleteUser(),
                defaults.export(),
                defaults.mentorTurns(),
                defaults.reviewRequest(),
                defaults.syncTrigger(),
                new AuthRateLimitProperties.Limit(1, Duration.ofHours(1)),
                defaults.browserReports());
        var limited = filter(configured);
        limited.doFilter(
                new MockHttpServletRequest("GET", "/public/workspaces/alpha/activity"),
                new MockHttpServletResponse(),
                mock(FilterChain.class));
        var blocked = new MockHttpServletResponse();
        limited.doFilter(
                new MockHttpServletRequest("HEAD", "/public/workspaces/alpha/activity/people/developer/work"),
                blocked,
                mock(FilterChain.class));
        assertThat(blocked.getStatus()).isEqualTo(429);
    }

    @Test
    void shouldNotChargeSignedInPublicReadsToTheAnonymousIpBudget() throws Exception {
        var limited = filter(props());
        authenticateAs("campus-member");
        var request = new MockHttpServletRequest("HEAD", "/public/workspaces/alpha/activity");
        limited.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(store).isEmpty();
        SecurityContextHolder.clearContext();
        limited.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(store).containsOnlyKeys("public-activity:ip:127.0.0.1");
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
        return props(
                overrides.length > 0 ? overrides[0] : new AuthRateLimitProperties.Limit(20, Duration.ofMinutes(1)),
                new AuthRateLimitProperties.Limit(30, Duration.ofMinutes(1)));
    }

    private static AuthRateLimitProperties props(
            AuthRateLimitProperties.Limit oauthAuthorization, AuthRateLimitProperties.Limit clientSession) {
        return new AuthRateLimitProperties(
                true,
                oauthAuthorization,
                new AuthRateLimitProperties.Limit(60, Duration.ofMinutes(1)),
                clientSession,
                new AuthRateLimitProperties.Limit(10, Duration.ofMinutes(1)),
                new AuthRateLimitProperties.Limit(3, Duration.ofHours(1)),
                new AuthRateLimitProperties.Limit(10, Duration.ofHours(1)),
                new AuthRateLimitProperties.Limit(20, Duration.ofMinutes(10)),
                new AuthRateLimitProperties.Limit(10, Duration.ofHours(1)),
                new AuthRateLimitProperties.Limit(10, Duration.ofHours(1)),
                new AuthRateLimitProperties.Limit(60, Duration.ofMinutes(1)),
                new AuthRateLimitProperties.Limit(2, Duration.ofMinutes(1)));
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
                new AuthRateLimitProperties.Limit(1, Duration.ofMinutes(1)),
                new AuthRateLimitProperties.Limit(1, Duration.ofHours(1)),
                new AuthRateLimitProperties.Limit(1, Duration.ofHours(1)),
                new AuthRateLimitProperties.Limit(1, Duration.ofHours(1)),
                new AuthRateLimitProperties.Limit(1, Duration.ofHours(1)),
                new AuthRateLimitProperties.Limit(1, Duration.ofHours(1)),
                new AuthRateLimitProperties.Limit(60, Duration.ofMinutes(1)),
                new AuthRateLimitProperties.Limit(1, Duration.ofMinutes(1)));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/refresh");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(disabled).doFilter(request, response, chain);
        filter(disabled).doFilter(request, response, chain);

        verify(chain, times(2)).doFilter(request, response);
        assertThat(store).isEmpty();
    }

    @Test
    void shouldCapPageReportsPerReaderAndLetThemPassWhenTheBackendFails() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        authenticateAs("reader");
        for (int i = 0; i < 2; i++) {
            filter(props())
                    .doFilter(new MockHttpServletRequest("POST", "/web-vitals"), new MockHttpServletResponse(), chain);
        }
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter(props()).doFilter(new MockHttpServletRequest("POST", "/web-vitals"), blocked, chain);

        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(store).containsOnlyKeys("browser-reports:acct:reader");
        assertThat(blockedCount("browser-reports")).isEqualTo(1);

        MockHttpServletResponse unavailable = new MockHttpServletResponse();
        new AuthRateLimitFilter(
                        props(),
                        (key, config) -> {
                            throw new IllegalStateException("store unreachable");
                        },
                        objectMapper,
                        metrics)
                .doFilter(new MockHttpServletRequest("POST", "/web-vitals"), unavailable, chain);
        assertThat(unavailable.getStatus()).isEqualTo(200);
        verify(chain, times(3)).doFilter(any(), any());
    }

    @Test
    void shouldCapInstalledClientSessionPostsPerIpWhenTheBudgetIsSpent() throws Exception {
        AuthRateLimitFilter filter = filter(props(
                new AuthRateLimitProperties.Limit(20, Duration.ofMinutes(1)),
                new AuthRateLimitProperties.Limit(2, Duration.ofMinutes(1))));
        FilterChain chain = mock(FilterChain.class);
        for (String path : List.of("/auth/client/token", "/auth/client/refresh")) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
            request.setRemoteAddr("198.51.100.7");
            filter.doFilter(request, new MockHttpServletResponse(), chain);
        }
        MockHttpServletRequest third = new MockHttpServletRequest("POST", "/auth/client/logout");
        third.setRemoteAddr("198.51.100.7");
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(third, blocked, chain);

        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(store).containsKey("client-session:ip:198.51.100.7");

        MockHttpServletRequest discovery = new MockHttpServletRequest("GET", "/auth/client/configuration");
        discovery.setRemoteAddr("198.51.100.7");
        MockHttpServletResponse passed = new MockHttpServletResponse();
        filter.doFilter(discovery, passed, chain);
        assertThat(passed.getStatus()).isEqualTo(200);
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
        assertThat(res.getContentAsString()).contains("Too many requests").contains("retryAfterSeconds");
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
        assertThat(config).isNotNull();
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
                "/workspaces/demo/mentor/turns",
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
                new MockHttpServletRequest("GET", "/workspaces/example/user-view/users/1"),
                new MockHttpServletResponse(),
                mock(FilterChain.class));
        MockHttpServletRequest activity =
                new MockHttpServletRequest("GET", "/workspaces/example/activity/members/alex/open-work");
        activity.addHeader("X-User-View-Account", "1");
        f.doFilter(activity, new MockHttpServletResponse(), mock(FilterChain.class));
        MockHttpServletRequest conversation =
                new MockHttpServletRequest("GET", "/workspaces/other/mentor/conversation");
        conversation.addHeader("X-User-View-Account", "2");
        f.doFilter(conversation, new MockHttpServletResponse(), mock(FilterChain.class));
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

        verify(blockedChain, never()).doFilter(any(), any());
        assertThat(blocked.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(store).containsOnlyKeys("delete-user:acct:7");
        var config = capturedConfigs.get("delete-user:acct:7");
        assertThat(config).isNotNull();
        assertThat(config.getBandwidths()[0].getCapacity()).isEqualTo(3);
    }

    @Test
    void webhookAndActuatorPathsAreNeverRateLimited() throws Exception {
        AuthRateLimitFilter f = filter(props());

        for (String path : new String[] {"/webhooks/github", "/actuator/health"}) {
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
        // capacity 1: an attacker rotating the XFF value across requests must still collapse into
        // the
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

    @ParameterizedTest
    @CsvSource({"POST,/workspaces/demo/mentor/turns", "GET,/public/workspaces/demo/activity"})
    void costlyRequestFailsClosedWhenBucketBackendThrows(String method, String path) throws Exception {
        if (!path.startsWith("/public/")) authenticateAs("42");
        BucketResolver throwing = (key, config) -> {
            throw new RuntimeException("bucket store down");
        };
        AuthRateLimitFilter f = new AuthRateLimitFilter(props(), throwing, objectMapper, metrics);
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    }

    @Test
    void viewedReadFailsClosedWhenBucketBackendThrows() throws Exception {
        authenticateAs("42");
        BucketResolver throwing = (key, config) -> {
            throw new RuntimeException("bucket store down");
        };
        AuthRateLimitFilter filter = new AuthRateLimitFilter(props(), throwing, objectMapper, metrics);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/workspaces/demo/activity/members/alex/open-work");
        request.addHeader("X-User-View-Account", "7");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    }

    @Test
    void shouldLogOnlyTheEndpointAndRetryWhenAnAccountOrIpBucketIsExhausted() throws Throwable {
        AuthRateLimitFilter f = filter(props(new AuthRateLimitProperties.Limit(1, Duration.ofMinutes(1))));
        MockHttpServletResponse accountBlocked = new MockHttpServletResponse();
        MockHttpServletResponse ipBlocked = new MockHttpServletResponse();

        List<ILoggingEvent> warnings = rateLimitWarnings(() -> {
            authenticateAs(ACCOUNT_CANARY);
            for (int i = 0; i < 3; i++) {
                f.doFilter(
                        new MockHttpServletRequest("DELETE", "/user"),
                        new MockHttpServletResponse(),
                        mock(FilterChain.class));
            }
            f.doFilter(new MockHttpServletRequest("DELETE", "/user"), accountBlocked, mock(FilterChain.class));
            SecurityContextHolder.clearContext();
            for (MockHttpServletResponse response : List.of(new MockHttpServletResponse(), ipBlocked)) {
                MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/github");
                request.setRemoteAddr(IP_CANARY);
                f.doFilter(request, response, mock(FilterChain.class));
            }
        });

        assertThat(accountBlocked.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(ipBlocked.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(store).containsKeys("delete-user:acct:" + ACCOUNT_CANARY, "oauth-authz:ip:" + IP_CANARY);
        assertThat(warnings)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(
                        "Rate limit exceeded: endpoint=DELETE_USER retryAfterSeconds="
                                + accountBlocked.getHeader(HttpHeaders.RETRY_AFTER),
                        "Rate limit exceeded: endpoint=OAUTH_AUTHORIZATION retryAfterSeconds="
                                + ipBlocked.getHeader(HttpHeaders.RETRY_AFTER));
        assertThat(warnings).allSatisfy(AuthRateLimitFilterTest::assertNoCanary);
    }

    @Test
    void shouldLogTheBackendFailureWithoutTheAccountOrIpBucketKey() throws Throwable {
        BucketResolver throwing = (key, config) -> {
            throw new IllegalStateException("bucket store down");
        };
        AuthRateLimitFilter f = new AuthRateLimitFilter(props(), throwing, objectMapper, metrics);
        MockHttpServletResponse refresh = new MockHttpServletResponse();
        MockHttpServletResponse authorization = new MockHttpServletResponse();

        List<ILoggingEvent> warnings = rateLimitWarnings(() -> {
            authenticateAs(ACCOUNT_CANARY);
            f.doFilter(new MockHttpServletRequest("POST", "/auth/refresh"), refresh, mock(FilterChain.class));
            SecurityContextHolder.clearContext();
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/github");
            request.setRemoteAddr(IP_CANARY);
            f.doFilter(request, authorization, mock(FilterChain.class));
        });

        // Both endpoints fail open, so the request still reaches the chain.
        assertThat(refresh.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(authorization.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(warnings)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(
                        "Rate limit backend error: endpoint=REFRESH",
                        "Rate limit backend error: endpoint=OAUTH_AUTHORIZATION");
        assertThat(warnings).allSatisfy(event -> {
            assertNoCanary(event);
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
        });
    }

    private static void assertNoCanary(ILoggingEvent event) {
        assertThat(event.getFormattedMessage()).doesNotContain(ACCOUNT_CANARY).doesNotContain(IP_CANARY);
        assertThat(Arrays.toString(event.getArgumentArray()))
                .doesNotContain(ACCOUNT_CANARY)
                .doesNotContain(IP_CANARY);
    }

    private static List<ILoggingEvent> rateLimitWarnings(Executable action) throws Throwable {
        Logger logger = (Logger) LoggerFactory.getLogger(AuthRateLimitFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.execute();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list.stream()
                .filter(event -> event.getMessage().startsWith("Rate limit"))
                .toList();
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
