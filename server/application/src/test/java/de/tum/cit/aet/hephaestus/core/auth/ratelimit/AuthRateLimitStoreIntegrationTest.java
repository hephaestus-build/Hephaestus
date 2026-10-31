package de.tum.cit.aet.hephaestus.core.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.core.auth.metrics.AuthMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * The instance's only bucket store is PostgreSQL: two filters that share the context's resolver
 * behave like two server replicas and draw on one persisted budget.
 */
class AuthRateLimitStoreIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private BucketResolver buckets;

    @Autowired
    private AuthRateLimitProperties configured;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthMetrics metrics;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldShareOneLimitAcrossReplicasThroughThePostgresStore() throws Exception {
        // The test profile turns the filter off; this test turns it on with a small sign-in budget.
        var properties = new AuthRateLimitProperties(
                true,
                new AuthRateLimitProperties.Limit(2, Duration.ofHours(1)),
                configured.refresh(),
                configured.clientSession(),
                configured.userView(),
                configured.deleteUser(),
                configured.export(),
                configured.mentorTurns(),
                configured.reviewRequest(),
                configured.syncTrigger(),
                configured.publicActivity(),
                configured.browserReports());
        var first = new AuthRateLimitFilter(properties, buckets, objectMapper, metrics);
        var second = new AuthRateLimitFilter(properties, buckets, objectMapper, metrics);
        String clientIp = "2001:db8::" + Integer.toHexString(UUID.randomUUID().hashCode() & 0xffff);
        String key = "oauth-authz:ip:" + clientIp;
        try {
            assertThat(signIn(first, clientIp).getStatus()).isEqualTo(200);
            assertThat(signIn(second, clientIp).getStatus()).isEqualTo(200);
            assertThat(signIn(first, clientIp).getStatus()).isEqualTo(429);
            assertThat(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM auth_rate_limit_bucket WHERE id = ? AND state IS NOT NULL AND expires_at IS NOT NULL",
                            Long.class,
                            key))
                    .isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM auth_rate_limit_bucket WHERE id = ?", key);
        }
    }

    private static MockHttpServletResponse signIn(AuthRateLimitFilter filter, String clientIp) throws Exception {
        var request = new MockHttpServletRequest("GET", "/oauth2/authorization/github");
        request.setRemoteAddr(clientIp);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, mock(FilterChain.class));
        return response;
    }
}
