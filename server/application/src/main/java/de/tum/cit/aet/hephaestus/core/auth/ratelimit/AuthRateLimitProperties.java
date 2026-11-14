package de.tum.cit.aet.hephaestus.core.auth.ratelimit;

import io.github.bucket4j.BucketConfiguration;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rate-limit knobs for sensitive and resource-intensive endpoints, bound to {@code hephaestus.auth.rate-limit.*}.
 *
 * <p>Each limit is a token bucket of {@code capacity} tokens that refills the full capacity once
 * per {@code period} (interval refill — a fresh budget every window, not a trickle). {@code application.yml}
 * holds every value and its environment variable. This record holds no defaults: a missing value stops startup, so
 * a deleted line cannot loosen a limit.
 *
 * <ul>
 *   <li>{@code GET /oauth2/authorization/*} — keyed by client IP</li>
 *   <li>{@code POST /auth/refresh} — keyed by account (JWT sub; IP fallback)</li>
 *   <li>{@code POST /auth/client/token|refresh|logout} — keyed by client IP</li>
 *   <li>User-view selection and normal-app reads — keyed by admin account</li>
 *   <li>{@code DELETE /user} — keyed by account</li>
 *   <li>{@code POST /user/exports} — keyed by account</li>
 *   <li>{@code POST /web-vitals} and {@code POST /browser-faults} — keyed by account, IP fallback</li>
 * </ul>
 *
 * <p>Set {@code enabled=false} to bypass the filter entirely (e.g. load tests). The limits are
 * shared across replicas through the PostgreSQL store in {@link AuthRateLimitConfig}.
 *
 * <p>Proxy trust is owned by the servlet container via {@code server.forward-headers-strategy: native}
 * (prod), which rewrites {@code getRemoteAddr()} to the real client before the filter runs; the filter
 * keys IP buckets purely off {@code getRemoteAddr()}. Configure proxy topology via
 * {@code server.tomcat.remoteip.*}, not here.
 *
 * @param enabled Turns on rate limits for sign-in, session and other sensitive endpoints. Turn it off only for load
 *     tests.
 */
@ConfigurationProperties(prefix = AuthRateLimitProperties.PREFIX)
public record AuthRateLimitProperties(
        Boolean enabled,
        Limit oauthAuthorization,
        Limit refresh,
        Limit clientSession,
        Limit userView,
        Limit deleteUser,
        Limit export,
        Limit mentorTurns,
        Limit reviewRequest,
        Limit syncTrigger,
        Limit publicActivity,
        Limit browserReports) {
    static final String PREFIX = "hephaestus.auth.rate-limit";

    public AuthRateLimitProperties {
        required(enabled, "enabled");
        required(oauthAuthorization, "oauth-authorization");
        required(refresh, "refresh");
        required(clientSession, "client-session");
        required(userView, "user-view");
        required(deleteUser, "delete-user");
        required(export, "export");
        required(mentorTurns, "mentor-turns");
        required(reviewRequest, "review-request");
        required(syncTrigger, "sync-trigger");
        required(publicActivity, "public-activity");
        required(browserReports, "browser-reports");
    }

    private static void required(Object value, String key) {
        if (value == null) {
            throw new IllegalArgumentException(PREFIX + "." + key + " is not set");
        }
    }

    /**
     * A single token-bucket limit: {@code capacity} requests per {@code period}.
     *
     * @param capacity Most requests that one key may send within one period. Must be at least 1.
     * @param period   Refill window of the limit. Must be positive.
     */
    public record Limit(long capacity, Duration period) {
        public Limit {
            if (capacity < 1) {
                throw new IllegalArgumentException("rate-limit capacity must be >= 1, got: " + capacity);
            }
            if (period == null || period.isZero() || period.isNegative()) {
                throw new IllegalArgumentException("rate-limit period must be positive, got: " + period);
            }
        }

        /**
         * This limit as a Bucket4j configuration: one bandwidth with an interval refill, so a caller
         * gets a fresh budget each window rather than a trickle. Every limiter in the instance builds
         * its buckets from here, so two limits with the same numbers behave the same way.
         */
        public BucketConfiguration bucketConfiguration() {
            return BucketConfiguration.builder()
                    .addLimit(limit -> limit.capacity(capacity).refillIntervally(capacity, period))
                    .build();
        }
    }
}
