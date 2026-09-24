package de.tum.cit.aet.hephaestus.core.auth.ratelimit;

import io.github.bucket4j.BucketConfiguration;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Rate-limit knobs for sensitive and resource-intensive endpoints, bound to {@code hephaestus.auth.rate-limit.*}.
 *
 * <p>Each limit is a token bucket of {@code capacity} tokens that refills the full capacity once
 * per {@code period} (interval refill — a fresh budget every window, not a trickle). Defaults match
 * the security-hardening spec (ADR 0017 follow-up):
 *
 * <ul>
 *   <li>{@code GET /oauth2/authorization/*} — 20/min, keyed by client IP</li>
 *   <li>{@code POST /auth/refresh} — 60/min, keyed by account (JWT sub; IP fallback)</li>
 *   <li>{@code GET /workspaces/{slug}/user-view/users/**} — 120/min, keyed by admin account</li>
 *   <li>{@code DELETE /user} — 3/hour, keyed by account</li>
 *   <li>{@code POST /user/exports} — 10/hour, keyed by account</li>
 * </ul>
 *
 * <p>Set {@code enabled=false} to bypass the filter entirely (e.g. load tests). The limits are
 * shared across replicas when a Postgres-backed {@code ProxyManager} is wired (production); see
 * {@link AuthRateLimitConfig} for the per-replica fallback trade-off.
 *
 * <p>Proxy trust is owned by the servlet container via {@code server.forward-headers-strategy: native}
 * (prod), which rewrites {@code getRemoteAddr()} to the real client before the filter runs; the filter
 * keys IP buckets purely off {@code getRemoteAddr()}. Configure proxy topology via
 * {@code server.tomcat.remoteip.*}, not here.
 */
@ConfigurationProperties(prefix = "hephaestus.auth.rate-limit")
public record AuthRateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue Limit oauthAuthorization,
        @DefaultValue Limit refresh,
        @DefaultValue Limit userView,
        @DefaultValue Limit deleteUser,
        @DefaultValue Limit export,
        @DefaultValue Limit mentorChat,
        @DefaultValue Limit reviewRequest,
        @DefaultValue Limit syncTrigger) {
    public AuthRateLimitProperties {
        // Bind nulls (a partially-specified YAML block) to the spec defaults so a misconfigured
        // sub-key never silently disables a limit.
        oauthAuthorization = oauthAuthorization != null ? oauthAuthorization : Limit.of(20, Duration.ofMinutes(1));
        refresh = refresh != null ? refresh : Limit.of(60, Duration.ofMinutes(1));
        userView = userView != null ? userView : Limit.of(120, Duration.ofMinutes(1));
        deleteUser = deleteUser != null ? deleteUser : Limit.of(3, Duration.ofHours(1));
        // 10/hour: generous for legit "download my data" (POST + a few download polls) but caps a
        // session from spamming async bundle assemblies (each persisting a BYTEA blob).
        export = export != null ? export : Limit.of(10, Duration.ofHours(1));
        mentorChat = mentorChat != null ? mentorChat : Limit.of(20, Duration.ofMinutes(10));
        reviewRequest = reviewRequest != null ? reviewRequest : Limit.of(10, Duration.ofHours(1));
        syncTrigger = syncTrigger != null ? syncTrigger : Limit.of(10, Duration.ofHours(1));
    }

    /**
     * A single token-bucket limit: {@code capacity} requests per {@code period}.
     *
     * @param capacity max requests permitted within one {@code period}; must be {@code >= 1}.
     * @param period   refill window; must be positive.
     */
    public record Limit(
            @DefaultValue("20") long capacity,
            @DefaultValue("1m") Duration period) {
        public Limit {
            if (capacity < 1) {
                throw new IllegalArgumentException("rate-limit capacity must be >= 1, got: " + capacity);
            }
            if (period == null || period.isZero() || period.isNegative()) {
                throw new IllegalArgumentException("rate-limit period must be positive, got: " + period);
            }
        }

        static Limit of(long capacity, Duration period) {
            return new Limit(capacity, period);
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
