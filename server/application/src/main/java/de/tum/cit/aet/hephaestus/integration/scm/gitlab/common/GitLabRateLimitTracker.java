package de.tum.cit.aet.hephaestus.integration.scm.gitlab.common;

import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.CRITICAL_REMAINING_THRESHOLD;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.DEFAULT_RATE_LIMIT;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.HEADER_RATE_LIMIT_LIMIT;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.HEADER_RATE_LIMIT_OBSERVED;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.HEADER_RATE_LIMIT_REMAINING;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.HEADER_RATE_LIMIT_RESET;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.LOW_REMAINING_THRESHOLD;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.spi.RateLimitSnapshot;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.metrics.GitLabMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Per-scope rate limit tracker for GitLab API with Micrometer metrics.
 *
 * <p>Unlike GitHub (which exposes rate limit data in GraphQL response fields),
 * GitLab exposes rate limits exclusively through HTTP response headers:
 * <ul>
 *   <li>{@code RateLimit-Remaining} — points remaining in current window</li>
 *   <li>{@code RateLimit-Limit} — total points allowed per window</li>
 *   <li>{@code RateLimit-Reset} — Unix timestamp when window resets</li>
 *   <li>{@code RateLimit-Observed} — points consumed by the current request</li>
 * </ul>
 *
 * <h2>Many GitLab instances report nothing at all</h2>
 * <p>GitLab's user/IP request throttles are <b>disabled by default on self-managed instances</b>, and an
 * instance with throttling off sends no {@code RateLimit-*} headers whatsoever. For such an instance
 * {@link #snapshot} stays {@code null} forever and the UI omits the row; any number rendered would be
 * invented.
 *
 * <p>GitLab defines no GraphQL points-per-minute quota — its GraphQL limits are per-query
 * <em>complexity</em> caps, and request-rate limiting is the generic REST/web throttle (2,000 requests per
 * minute for authenticated API traffic on GitLab.com). {@code DEFAULT_RATE_LIMIT} is therefore a
 * conservative internal pacing floor, not a vendor fact — see {@link #getRemaining}.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Uses {@link ConcurrentHashMap} for scope state
 * and atomic variables for individual state fields.
 *
 * <h2>Memory Management</h2>
 * <p>State entries are automatically evicted after 24 hours of inactivity.
 *
 * @see <a href="https://docs.gitlab.com/ee/api/graphql/index.html#limits">GitLab GraphQL Limits</a>
 * @see de.tum.cit.aet.hephaestus.integration.scm.github.common.ScopedRateLimitTracker
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
@WorkspaceAgnostic("System-wide rate limit tracking — tracks GitLab API limits per-scope across all workspaces")
public class GitLabRateLimitTracker {

    /**
     * Maximum time to wait when rate limit is critical.
     * GitLab resets every 60 seconds, so 70s gives a comfortable margin.
     */
    private static final Duration MAX_WAIT_DURATION = Duration.ofSeconds(70);

    /** Minimum wait to avoid busy-waiting. */
    private static final Duration MIN_WAIT_DURATION = Duration.ofSeconds(1);

    /** Time after which inactive scope state is evicted (24 hours). */
    private static final Duration EVICTION_THRESHOLD = Duration.ofHours(24);

    /** Metric name prefix for rate limit gauges. */
    private static final String METRIC_PREFIX = "gitlab.graphql.ratelimit";

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<Long, ScopeRateLimitState> stateByScope = new ConcurrentHashMap<>();

    public GitLabRateLimitTracker(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Updates rate limit state from HTTP response headers for a specific scope.
     *
     * @param scopeId the scope (workspace ID) that made the API call
     * @param headers the HTTP response headers containing rate limit data
     */
    public void updateFromHeaders(@Nullable Long scopeId, @Nullable HttpHeaders headers) {
        if (scopeId == null || headers == null) {
            return;
        }

        String remainingStr = headers.getFirst(HEADER_RATE_LIMIT_REMAINING);
        String limitStr = headers.getFirst(HEADER_RATE_LIMIT_LIMIT);
        String resetStr = headers.getFirst(HEADER_RATE_LIMIT_RESET);
        String observedStr = headers.getFirst(HEADER_RATE_LIMIT_OBSERVED);

        if (remainingStr == null && limitStr == null) {
            return; // No rate limit headers present
        }

        try {
            // Parse before touching state so a garbled header cannot leave a half-written observation.
            Integer parsedRemaining = remainingStr == null ? null : Integer.valueOf(remainingStr.trim());
            Integer parsedLimit = limitStr == null ? null : Integer.valueOf(limitStr.trim());
            Instant parsedReset = resetStr == null ? null : Instant.ofEpochSecond(Long.parseLong(resetStr.trim()));
            Integer parsedObserved = observedStr == null ? null : Integer.valueOf(observedStr.trim());

            ScopeRateLimitState state = getOrCreateState(scopeId);

            // Each field is written ONLY from its own header. A response carrying just one of the pair
            // (a stripping proxy, partial middleware) must leave the other unknown rather than fill it
            // from a constant.
            if (parsedRemaining != null) {
                state.remaining.set(parsedRemaining);
            }
            if (parsedLimit != null) {
                state.limit.set(parsedLimit);
            }
            if (parsedReset != null) {
                state.resetAt.set(parsedReset);
            }
            if (parsedObserved != null) {
                state.lastQueryCost.set(parsedObserved);
            }

            Integer currentLimit = state.limit.get();
            Integer currentRemaining = state.remaining.get();
            // "Used" is only computable when both sides were actually reported.
            if (currentLimit != null && currentRemaining != null) {
                state.used.set(currentLimit - currentRemaining);
            }
            state.lastUpdated.set(Instant.now());

            if (currentRemaining == null) {
                return; // nothing measured about the budget itself — nothing to judge severity on
            }

            // Log based on severity
            if (currentRemaining < CRITICAL_REMAINING_THRESHOLD) {
                log.error(
                        "CRITICAL: GitLab GraphQL rate limit nearly exhausted: scopeId={}, remaining={}, limit={}, resetAt={}",
                        scopeId,
                        currentRemaining,
                        currentLimit,
                        state.resetAt.get());
            } else if (currentRemaining < LOW_REMAINING_THRESHOLD) {
                log.warn(
                        "GitLab GraphQL rate limit low: scopeId={}, remaining={}, limit={}, resetAt={}",
                        scopeId,
                        currentRemaining,
                        currentLimit,
                        state.resetAt.get());
            }
        } catch (NumberFormatException e) {
            log.debug("Could not parse GitLab rate limit headers for scope {}: {}", scopeId, e.getMessage());
        }
    }

    /**
     * The remaining points to <b>throttle against</b> — a decision value, never a reported one.
     *
     * <p>Falls back to {@code DEFAULT_RATE_LIMIT} when GitLab has reported nothing. That constant is a
     * conservative pacing floor, not a vendor guarantee: GitLab publishes no GraphQL points-per-minute
     * quota, and GitLab.com's authenticated API throttle is 2,000 requests per minute. Being an
     * assumption, it must never reach {@link #snapshot}, which reports only observed values.
     */
    public int getRemaining(@Nullable Long scopeId) {
        if (scopeId == null) {
            return DEFAULT_RATE_LIMIT;
        }
        ScopeRateLimitState state = stateByScope.get(scopeId);
        if (state == null) {
            return DEFAULT_RATE_LIMIT;
        }
        return effectiveRemaining(state);
    }

    /** Gets the total rate limit for a scope, falling back to the conservative pacing floor. */
    public int getLimit(Long scopeId) {
        if (scopeId == null) {
            return DEFAULT_RATE_LIMIT;
        }
        ScopeRateLimitState state = stateByScope.get(scopeId);
        return state == null ? DEFAULT_RATE_LIMIT : limitOrDefault(state);
    }

    /**
     * Optimistic reset: once the observed window has closed the measured remaining is not a fact about
     * the current window, so decisions assume a full budget. Computed, never written back, so the
     * heuristic never surfaces as a measured value on {@link #snapshot}.
     */
    private static int effectiveRemaining(ScopeRateLimitState state) {
        Integer observed = state.remaining.get();
        if (observed == null) {
            return limitOrDefault(state);
        }
        if (observed < LOW_REMAINING_THRESHOLD) {
            Instant resetAt = state.resetAt.get();
            if (resetAt != null && !Instant.now().isBefore(resetAt)) {
                return limitOrDefault(state);
            }
        }
        return observed;
    }

    private static int limitOrDefault(ScopeRateLimitState state) {
        Integer limit = state.limit.get();
        return limit == null ? DEFAULT_RATE_LIMIT : limit;
    }

    /** Checks if the rate limit is critically low. */
    public boolean isCritical(Long scopeId) {
        return getRemaining(scopeId) < CRITICAL_REMAINING_THRESHOLD;
    }

    /** Checks if the rate limit is below the low threshold. */
    public boolean isLow(Long scopeId) {
        return getRemaining(scopeId) < LOW_REMAINING_THRESHOLD;
    }

    /**
     * Waits if the rate limit is critical, blocking until reset.
     *
     * @return true if waited, false if no wait was needed
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean waitIfNeeded(Long scopeId) throws InterruptedException {
        int currentRemaining = getRemaining(scopeId);

        if (currentRemaining >= LOW_REMAINING_THRESHOLD) {
            return false;
        }

        if (currentRemaining >= CRITICAL_REMAINING_THRESHOLD) {
            log.info(
                    "GitLab rate limit low but continuing: scopeId={}, remaining={}, threshold={}",
                    scopeId,
                    currentRemaining,
                    LOW_REMAINING_THRESHOLD);
            return false;
        }

        // Critical — need to wait
        Instant reset = getResetAt(scopeId);
        if (reset == null) {
            log.warn("GitLab rate limit critical but no reset time, waiting minimum: scopeId={}", scopeId);
            Thread.sleep(MIN_WAIT_DURATION.toMillis());
            return true;
        }

        Duration waitTime = Duration.between(Instant.now(), reset);

        if (waitTime.isNegative() || waitTime.isZero()) {
            // Observed window closed — nothing to wait for. No write-back: effectiveRemaining() already
            // treats a closed window as "assume full" for decisions, and the next response supplies the
            // real number, so no invented value can reach the reporting snapshot.
            log.info("GitLab rate limit reset time passed, continuing: scopeId={}, resetAt={}", scopeId, reset);
            return false;
        }

        if (waitTime.compareTo(MAX_WAIT_DURATION) > 0) {
            waitTime = MAX_WAIT_DURATION;
        }

        log.warn(
                "Pausing due to critical GitLab rate limit: scopeId={}, remaining={}, waitSeconds={}, resetAt={}",
                scopeId,
                currentRemaining,
                waitTime.toSeconds(),
                reset);

        Thread.sleep(waitTime.toMillis());
        return true;
    }

    /** Gets the reset time for a scope's rate limit. */
    @Nullable
    public Instant getResetAt(Long scopeId) {
        if (scopeId == null) {
            return null;
        }
        ScopeRateLimitState state = stateByScope.get(scopeId);
        return state != null ? state.resetAt.get() : null;
    }

    /**
     * Gets recommended delay based on rate limit state.
     *
     * @return recommended delay, or Duration.ZERO if no throttling needed
     */
    public Duration getRecommendedDelay(Long scopeId) {
        int currentRemaining = getRemaining(scopeId);

        if (currentRemaining >= LOW_REMAINING_THRESHOLD) {
            return Duration.ZERO;
        }

        Instant reset = getResetAt(scopeId);
        if (reset == null || reset.isBefore(Instant.now())) {
            return Duration.ZERO;
        }

        Duration timeUntilReset = Duration.between(Instant.now(), reset);

        if (currentRemaining <= 0) {
            return timeUntilReset.compareTo(MAX_WAIT_DURATION) > 0 ? MAX_WAIT_DURATION : timeUntilReset;
        }

        // Distribute remaining points evenly across remaining time
        int effectiveRemaining = Math.max(1, currentRemaining - CRITICAL_REMAINING_THRESHOLD);
        long delayMillis = timeUntilReset.toMillis() / effectiveRemaining;

        delayMillis = Math.max(MIN_WAIT_DURATION.toMillis(), delayMillis);
        delayMillis = Math.min(MAX_WAIT_DURATION.toMillis(), delayMillis);

        return Duration.ofMillis(delayMillis);
    }

    /** Gets the number of tracked scopes (for monitoring/debugging). */
    public int getTrackedScopeCount() {
        return stateByScope.size();
    }

    /**
     * Point-in-time snapshot for the sync-observability provider — {@code null} if this scope has never
     * had a real rate-limit header observed since the last restart (as opposed to the
     * {@link #getRemaining}/{@link #getLimit} pacing defaults, which drive throttling decisions and would
     * otherwise misrepresent "unknown" as "100/100 available"). State is created only from
     * {@link #updateFromHeaders} once at least one real header is present, so {@code state == null}
     * already means "never observed" — no separate flag needed.
     *
     * <p>Each field is reported independently, so a partial header set surfaces as a partial fact rather
     * than a whole invented one. The window-expiry rule is applied centrally by
     * {@link RateLimitSnapshot#observed}.
     */
    public @Nullable RateLimitSnapshot snapshot(@Nullable Long scopeId) {
        if (scopeId == null) {
            return null;
        }
        ScopeRateLimitState state = stateByScope.get(scopeId);
        if (state == null) {
            return null;
        }
        Instant observedAt = state.lastUpdated.get();
        if (observedAt == null) {
            return null;
        }
        return RateLimitSnapshot.observed(
                state.limit.get(), state.remaining.get(), state.resetAt.get(), observedAt, null);
    }

    /**
     * Evicts stale scope state to prevent unbounded memory growth.
     * Runs every hour, removes entries inactive for 24+ hours.
     */
    @Scheduled(fixedRate = 3600000) // 1 hour
    public void evictStaleEntries() {
        Instant threshold = Instant.now().minus(EVICTION_THRESHOLD);
        List<Long> scopesToEvict = new ArrayList<>();

        for (Map.Entry<Long, ScopeRateLimitState> entry : stateByScope.entrySet()) {
            Instant lastUpdated = entry.getValue().lastUpdated.get();
            if (lastUpdated != null && lastUpdated.isBefore(threshold)) {
                scopesToEvict.add(entry.getKey());
            }
        }

        if (scopesToEvict.isEmpty()) {
            log.debug("GitLab rate limit eviction: no stale entries, tracked scopes={}", stateByScope.size());
            return;
        }

        for (Long scopeId : scopesToEvict) {
            stateByScope.remove(scopeId);
            deregisterMetrics(scopeId);
            log.info("Evicted stale GitLab rate limit state: scopeId={}", scopeId);
        }

        log.info(
                "GitLab rate limit eviction completed: evicted={}, remaining={}",
                scopesToEvict.size(),
                stateByScope.size());
    }

    private void deregisterMetrics(Long scopeId) {
        String scopeTag = String.valueOf(scopeId);
        List<Meter.Id> toRemove = new ArrayList<>();

        meterRegistry.getMeters().forEach(meter -> {
            String meterName = meter.getId().getName();
            if (meterName.startsWith(METRIC_PREFIX)) {
                String tagValue = meter.getId().getTag("scope_id");
                if (scopeTag.equals(tagValue)) {
                    toRemove.add(meter.getId());
                }
            }
        });

        for (Meter.Id meterId : toRemove) {
            meterRegistry.remove(meterId);
        }
    }

    private ScopeRateLimitState getOrCreateState(Long scopeId) {
        return stateByScope.computeIfAbsent(scopeId, id -> {
            ScopeRateLimitState state = new ScopeRateLimitState();
            registerMetrics(id, state);
            log.debug("Created GitLab rate limit state for scope: scopeId={}", id);
            return state;
        });
    }

    private void registerMetrics(Long scopeId, ScopeRateLimitState state) {
        Tags tags = Tags.of("scope_id", String.valueOf(scopeId));

        // NaN, not 0, while unobserved: a gauge is a measurement too, and 0 would read as "exhausted".
        Gauge.builder(GitLabMetrics.GITLAB_GRAPHQL_RATELIMIT_POINTS_REMAINING, state, s -> asDouble(s.remaining.get()))
                .tags(tags)
                .description("GitLab GraphQL API rate limit points remaining")
                .register(meterRegistry);

        Gauge.builder(GitLabMetrics.GITLAB_GRAPHQL_RATELIMIT_POINTS_LIMIT, state, s -> asDouble(s.limit.get()))
                .tags(tags)
                .description("GitLab GraphQL API rate limit total points per minute")
                .register(meterRegistry);

        Gauge.builder(GitLabMetrics.GITLAB_GRAPHQL_RATELIMIT_POINTS_USED, state.used, AtomicInteger::get)
                .tags(tags)
                .description("GitLab GraphQL API rate limit points used in current window")
                .register(meterRegistry);

        Gauge.builder(GitLabMetrics.GITLAB_GRAPHQL_RATELIMIT_LAST_QUERY_COST, state.lastQueryCost, AtomicInteger::get)
                .tags(tags)
                .description("Points consumed by the last GitLab GraphQL query")
                .register(meterRegistry);

        Gauge.builder(GitLabMetrics.GITLAB_GRAPHQL_RATELIMIT_SECONDS_UNTIL_RESET, state, s -> {
                    Instant reset = s.resetAt.get();
                    if (reset == null) {
                        return 0.0;
                    }
                    long seconds = Duration.between(Instant.now(), reset).toSeconds();
                    return Math.max(0, seconds);
                })
                .tags(tags)
                .description("Seconds until GitLab GraphQL rate limit resets")
                .register(meterRegistry);
    }

    /** Renders an unobserved value as NaN rather than a number that would be read as a measurement. */
    private static double asDouble(@Nullable Integer value) {
        return value == null ? Double.NaN : value.doubleValue();
    }

    /**
     * Rate limit state for a single scope. All fields are atomic for thread-safe concurrent access, and
     * every observable field starts <b>null</b>: {@link #updateFromHeaders} creates state as soon as
     * <em>either</em> count header is present, so seeding a constant would surface as an invented value
     * for whichever header the response omits.
     */
    private static class ScopeRateLimitState {

        final AtomicReference<Integer> remaining = new AtomicReference<>();
        final AtomicReference<Integer> limit = new AtomicReference<>();
        final AtomicInteger used = new AtomicInteger(0);
        final AtomicInteger lastQueryCost = new AtomicInteger(0);
        final AtomicReference<Instant> resetAt = new AtomicReference<>();
        final AtomicReference<Instant> lastUpdated = new AtomicReference<>();
    }
}
