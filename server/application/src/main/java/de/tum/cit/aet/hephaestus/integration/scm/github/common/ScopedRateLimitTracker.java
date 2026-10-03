package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.spi.RateLimitSnapshot;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHRateLimit;
import de.tum.cit.aet.hephaestus.integration.scm.github.metrics.GitHubMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Per-scope rate limit tracker with Micrometer metrics.
 *
 * <p>GitHub rate limits are per-installation (GitHub App) or per-token (PAT).
 * This tracker maintains separate state for each scope/workspace, ensuring
 * accurate throttling decisions in multi-tenant deployments.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Uses {@link ConcurrentHashMap} for scope state
 * and atomic variables for individual state fields. Multiple sync threads can
 * safely access and update the same scope's rate limit concurrently.
 *
 * <h2>Memory Management</h2>
 * <p>State entries are created on first access and automatically evicted after
 * 24 hours of inactivity. This prevents unbounded memory growth in multi-tenant
 * deployments with many short-lived workspaces. Eviction runs every hour.
 *
 * <h2>Metrics</h2>
 * <p>Micrometer gauges are registered per-scope with a {@code scope_id} tag,
 * enabling per-workspace monitoring in Prometheus/Grafana dashboards.
 *
 * @see RateLimitTracker
 */
@Component
@Slf4j
@WorkspaceAgnostic("System-wide rate limit tracking - tracks GitHub API limits per-scope across all workspaces")
public class ScopedRateLimitTracker implements RateLimitTracker, RateLimitObservationSink {

    /**
     * Conservative assumed ceiling for an <em>unobserved</em> scope, used only by the throttle-decision
     * APIs ({@link #getRemaining}, {@link #getLimit}, {@link #waitIfNeeded}, {@link #getRecommendedDelay}).
     *
     * <p>It is a guess, not a fact: GitHub's real GraphQL ceiling is 5,000 points/hour for a plain
     * installation but scales to 12,500 for installations with more than 20 repositories and is 10,000 on
     * Enterprise Cloud. It therefore must never reach {@link #snapshot} — see
     * {@link RateLimitSnapshot} for the honesty rule. The seeded observation from REST
     * {@code GET /rate_limit} (see {@link #updateFromRestRateLimit}) is what makes the true ceiling known
     * before the first sync.
     */
    private static final int DEFAULT_LIMIT = 5000;

    /** Low threshold below which we consider throttling (10% of default). */
    private static final int DEFAULT_LOW_THRESHOLD = 500;

    /** Critical threshold below which we pause completely (2% of default). */
    private static final int CRITICAL_THRESHOLD = 100;

    private static final Duration MAX_WAIT_DURATION = Duration.ofMinutes(5);

    /** Avoids busy-waiting when no reset time is available. */
    private static final Duration MIN_WAIT_DURATION = Duration.ofSeconds(1);

    private static final Duration EVICTION_THRESHOLD = Duration.ofHours(24);

    private static final String METRIC_PREFIX = "github.graphql.ratelimit";

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<Long, ScopeRateLimitState> stateByScope = new ConcurrentHashMap<>();

    public ScopedRateLimitTracker(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    @Nullable
    public GHRateLimit updateFromResponse(@Nullable Long scopeId, @Nullable ClientGraphQlResponse response) {
        if (scopeId == null || response == null || !response.isValid()) {
            return null;
        }

        try {
            GHRateLimit rateLimit = response.field("rateLimit").toEntity(GHRateLimit.class);
            if (rateLimit != null) {
                ScopeRateLimitState state = getOrCreateState(scopeId);
                state.update(rateLimit, scopeId);
                return rateLimit;
            }
        } catch (Exception e) {
            // Rate limit field may not be present in all queries
            log.trace("Could not extract rate limit from response for scope {}: {}", scopeId, e.getMessage());
        }
        return null;
    }

    @Override
    public int getRemaining(@Nullable Long scopeId) {
        if (scopeId == null) {
            return DEFAULT_LIMIT;
        }
        ScopeRateLimitState state = stateByScope.get(scopeId);
        if (state == null) {
            return DEFAULT_LIMIT;
        }
        return effectiveRemaining(state);
    }

    @Override
    public int getLimit(@Nullable Long scopeId) {
        if (scopeId == null) {
            return DEFAULT_LIMIT;
        }
        ScopeRateLimitState state = stateByScope.get(scopeId);
        return state == null ? DEFAULT_LIMIT : limitOrDefault(state);
    }

    /**
     * The remaining budget to <em>throttle against</em> — a pure computation, never a write. When the
     * observed window has already closed, the measured {@code remaining} says nothing about the current
     * window, so decisions assume a full budget rather than stalling on stale data. This must stay a
     * return value: {@link ScopeRateLimitState#update} is the only writer of observed fields, so an
     * invented "full" value can never reach {@link #snapshot}.
     */
    private static int effectiveRemaining(ScopeRateLimitState state) {
        Integer observed = state.remaining.get();
        if (observed == null) {
            return limitOrDefault(state);
        }
        if (observed < DEFAULT_LOW_THRESHOLD) {
            Instant resetAt = state.resetAt.get();
            if (resetAt != null && !Instant.now().isBefore(resetAt)) {
                return limitOrDefault(state);
            }
        }
        return observed;
    }

    /** The observed ceiling, or the conservative assumption when GitHub has not reported one yet. */
    private static int limitOrDefault(ScopeRateLimitState state) {
        Integer limit = state.limit.get();
        return limit == null ? DEFAULT_LIMIT : limit;
    }

    @Override
    public boolean isCritical(Long scopeId) {
        return getRemaining(scopeId) < CRITICAL_THRESHOLD;
    }

    @Override
    public boolean isLow(Long scopeId) {
        return getRemaining(scopeId) < DEFAULT_LOW_THRESHOLD;
    }

    @Override
    public boolean waitIfNeeded(Long scopeId) throws InterruptedException {
        int currentRemaining = getRemaining(scopeId);

        if (currentRemaining >= DEFAULT_LOW_THRESHOLD) {
            return false;
        }

        if (currentRemaining >= CRITICAL_THRESHOLD) {
            log.info(
                    "Rate limit low but continuing: scopeId={}, remaining={}, threshold={}",
                    scopeId,
                    currentRemaining,
                    DEFAULT_LOW_THRESHOLD);
            return false;
        }

        Instant reset = getResetAt(scopeId);
        if (reset == null) {
            log.warn("Rate limit critical but no reset time available, waiting minimum duration: scopeId={}", scopeId);
            Thread.sleep(MIN_WAIT_DURATION.toMillis());
            return true;
        }

        Duration waitTime = Duration.between(Instant.now(), reset);

        if (waitTime.isNegative() || waitTime.isZero()) {
            // The observed window has closed, so there is nothing to wait for. This does not write a full
            // budget back into the observed state: effectiveRemaining() already treats a closed window as
            // "assume full" for decisions, and the next GraphQL response supplies the real number, so the
            // admin-facing snapshot never sees an invented value.
            log.info("Rate limit reset time has passed, continuing: scopeId={}, resetAt={}", scopeId, reset);
            return false;
        }

        if (waitTime.compareTo(MAX_WAIT_DURATION) > 0) {
            waitTime = MAX_WAIT_DURATION;
        }

        log.warn(
                "Pausing due to critical rate limit: scopeId={}, remaining={}, waitSeconds={}, resetAt={}",
                scopeId,
                currentRemaining,
                waitTime.getSeconds(),
                reset);

        Thread.sleep(waitTime.toMillis());
        return true;
    }

    @Override
    @Nullable
    public Instant getResetAt(@Nullable Long scopeId) {
        if (scopeId == null) {
            return null;
        }
        ScopeRateLimitState state = stateByScope.get(scopeId);
        return state != null ? state.resetAt.get() : null;
    }

    @Override
    public Duration getRecommendedDelay(Long scopeId) {
        int currentRemaining = getRemaining(scopeId);

        if (currentRemaining >= DEFAULT_LOW_THRESHOLD) {
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

        // Distribute remaining points evenly across remaining time; subtracting CRITICAL_THRESHOLD
        // leaves a buffer so the delay doesn't shrink to zero right before the limit is exhausted.
        int effectiveRemaining = Math.max(1, currentRemaining - CRITICAL_THRESHOLD);
        long delayMillis = timeUntilReset.toMillis() / effectiveRemaining;

        delayMillis = Math.max(MIN_WAIT_DURATION.toMillis(), delayMillis);
        delayMillis = Math.min(MAX_WAIT_DURATION.toMillis(), delayMillis);

        return Duration.ofMillis(delayMillis);
    }

    /**
     * Seeds a real observation from REST {@code GET /rate_limit}'s {@code resources.graphql} entry.
     *
     * <p>This exists so the <em>true</em> ceiling is known before the first GraphQL call of a process —
     * see {@link GitHubRestRateLimitSeeder} for why GitHub's GraphQL ceiling is not a flat 5,000/hour and
     * why probing this endpoint is free. The values fed here were measured, not guessed.
     *
     * <p>Applied only if it is <em>newer</em> than whatever is already recorded, so a seed that loses a
     * race against a live GraphQL response cannot overwrite fresher data with staler data.
     *
     * @param scopeId    the scope the token belongs to
     * @param limit      {@code resources.graphql.limit}
     * @param remaining  {@code resources.graphql.remaining}
     * @param resetAt    {@code resources.graphql.reset}, or {@code null} if absent
     * @param observedAt when the REST response was received
     */
    @Override
    public void updateFromRestRateLimit(
            Long scopeId, int limit, int remaining, @Nullable Instant resetAt, Instant observedAt) {
        if (scopeId == null) {
            return;
        }
        ScopeRateLimitState state = getOrCreateState(scopeId);
        Instant lastUpdated = state.lastUpdated.get();
        if (lastUpdated != null && !observedAt.isAfter(lastUpdated)) {
            return; // a fresher observation already won the race
        }
        state.applyRest(limit, remaining, resetAt, observedAt);
        log.debug(
                "Seeded GitHub GraphQL rate limit from REST /rate_limit: scopeId={}, limit={}, remaining={}, resetAt={}",
                scopeId,
                limit,
                remaining,
                resetAt);
    }

    @Override
    public boolean hasObservation(Long scopeId) {
        return snapshot(scopeId) != null;
    }

    /**
     * Point-in-time rate-limit snapshot for a scope, for the sync-observability
     * {@code ConnectionSyncStateProvider#describe} read model.
     *
     * <p>Every value here was measured. It deliberately does NOT fall back to the assumptions that
     * {@link #getRemaining}/{@link #getLimit} return for an untracked scope: those exist so sync code has
     * a safe "assume full budget" value to throttle against, but surfacing them to an admin as a real
     * budget would misrepresent "nothing observed since the last restart" as "5000/5000 remaining". The
     * window-expiry rule is applied centrally by {@link RateLimitSnapshot#observed}.
     *
     * @return the snapshot, or {@code null} if nothing has been observed for this scope yet
     */
    @Nullable
    public RateLimitSnapshot snapshot(@Nullable Long scopeId) {
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
                state.limit.get(),
                state.remaining.get(),
                state.resetAt.get(),
                observedAt,
                // GitHub's secondary (abuse) limits, its only 429-style back-off signal, surface on the REST/GraphQL
                // transport rather than in the rateLimit field this tracker reads, so it has none to report.
                null);
    }

    public int getTrackedScopeCount() {
        return stateByScope.size();
    }

    /** Also deregisters the associated Micrometer metrics to prevent gauge leaks. */
    @Scheduled(fixedRate = 3600000) // 1 hour in milliseconds
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
            log.debug("Rate limit eviction: no stale entries found, tracked scopes={}", stateByScope.size());
            return;
        }

        for (Long scopeId : scopesToEvict) {
            stateByScope.remove(scopeId);
            deregisterMetrics(scopeId);
            log.info("Evicted stale rate limit state: scopeId={}", scopeId);
        }

        log.info("Rate limit eviction completed: evicted={}, remaining={}", scopesToEvict.size(), stateByScope.size());
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

        log.debug("Deregistered {} metrics for scopeId={}", toRemove.size(), scopeId);
    }

    private ScopeRateLimitState getOrCreateState(Long scopeId) {
        return stateByScope.computeIfAbsent(scopeId, id -> {
            ScopeRateLimitState state = new ScopeRateLimitState();
            registerMetrics(id, state);
            log.debug("Created rate limit state for scope: scopeId={}", id);
            return state;
        });
    }

    private void registerMetrics(Long scopeId, ScopeRateLimitState state) {
        Tags tags = Tags.of("scope_id", String.valueOf(scopeId));

        // NaN, not 0, while unobserved: a gauge is a measurement too, and 0 would read as "exhausted".
        Gauge.builder(GitHubMetrics.GITHUB_GRAPHQL_RATELIMIT_POINTS_REMAINING, state, s -> asDouble(s.remaining.get()))
                .tags(tags)
                .description("GitHub GraphQL API rate limit points remaining")
                .register(meterRegistry);

        Gauge.builder(GitHubMetrics.GITHUB_GRAPHQL_RATELIMIT_POINTS_LIMIT, state, s -> asDouble(s.limit.get()))
                .tags(tags)
                .description("GitHub GraphQL API rate limit total points per hour")
                .register(meterRegistry);

        Gauge.builder(GitHubMetrics.GITHUB_GRAPHQL_RATELIMIT_POINTS_USED, state.used, AtomicInteger::get)
                .tags(tags)
                .description("GitHub GraphQL API rate limit points used in current window")
                .register(meterRegistry);

        Gauge.builder(GitHubMetrics.GITHUB_GRAPHQL_RATELIMIT_LAST_QUERY_COST, state.lastQueryCost, AtomicInteger::get)
                .tags(tags)
                .description("Cost of the last GitHub GraphQL query")
                .register(meterRegistry);

        Gauge.builder(GitHubMetrics.GITHUB_GRAPHQL_RATELIMIT_SECONDS_UNTIL_RESET, state, s -> {
                    Instant reset = s.resetAt.get();
                    if (reset == null) {
                        return 0.0;
                    }
                    long seconds = Duration.between(Instant.now(), reset).getSeconds();
                    return Math.max(0, seconds);
                })
                .tags(tags)
                .description("Seconds until GitHub GraphQL rate limit resets")
                .register(meterRegistry);
    }

    /** Renders an unobserved value as NaN rather than a number that would be read as a measurement. */
    private static double asDouble(@Nullable Integer value) {
        return value == null ? Double.NaN : value.doubleValue();
    }

    /**
     * Rate limit state for a single scope. All fields are atomic for thread-safe concurrent access, and
     * every observable field starts <b>null</b>: nothing here is a fact until GitHub reports one, so a
     * caller can never observe a fabricated limit/remaining/resetAt.
     */
    private static class ScopeRateLimitState {

        final AtomicReference<Integer> remaining = new AtomicReference<>();
        final AtomicReference<Integer> limit = new AtomicReference<>();
        final AtomicInteger used = new AtomicInteger(0);
        final AtomicInteger lastQueryCost = new AtomicInteger(0);
        final AtomicReference<Instant> resetAt = new AtomicReference<>();
        final AtomicReference<Instant> lastUpdated = new AtomicReference<>();

        /** Records a REST {@code GET /rate_limit} observation of the {@code graphql} resource. */
        void applyRest(int newLimit, int newRemaining, @Nullable Instant newResetAt, Instant observedAt) {
            remaining.set(newRemaining);
            limit.set(newLimit);
            used.set(Math.max(0, newLimit - newRemaining));
            if (newResetAt != null) {
                resetAt.set(newResetAt);
            }
            lastUpdated.set(observedAt);
        }

        void update(GHRateLimit rateLimit, Long scopeId) {
            int newRemaining = rateLimit.getRemaining();
            int newLimit = rateLimit.getLimit();
            int newUsed = rateLimit.getUsed();
            int cost = rateLimit.getCost();

            remaining.set(newRemaining);
            limit.set(newLimit);
            used.set(newUsed);
            lastQueryCost.set(cost);

            OffsetDateTime resetTime = rateLimit.getResetAt();
            if (resetTime != null) {
                resetAt.set(resetTime.toInstant());
            }
            lastUpdated.set(Instant.now());

            // limit - remaining is more reliable than the reported used count
            int actualUsed = newLimit > 0 ? newLimit - newRemaining : 0;

            if (newRemaining < CRITICAL_THRESHOLD) {
                log.error(
                        "CRITICAL: GitHub GraphQL rate limit nearly exhausted: scopeId={}, remaining={}, limit={}, used={}, cost={}, resetAt={}",
                        scopeId,
                        newRemaining,
                        newLimit,
                        actualUsed,
                        cost,
                        resetTime);
            } else if (newRemaining < DEFAULT_LOW_THRESHOLD) {
                log.warn(
                        "GitHub GraphQL rate limit low: scopeId={}, remaining={}, limit={}, used={}, cost={}, resetAt={}",
                        scopeId,
                        newRemaining,
                        newLimit,
                        actualUsed,
                        cost,
                        resetTime);
            }
        }
    }
}
