package de.tum.cit.aet.hephaestus.integration.outline.client;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.spi.RateLimitSnapshot;
import de.tum.cit.aet.hephaestus.integration.outline.metrics.OutlineMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * Per-scope Outline API rate-limit tracker. Purely observational: Outline throttling is enforced by the
 * {@code Retry-After}-honoring 429 handling in {@link OutlineApiClient}, so this class never blocks or
 * paces — it only records what Outline actually told us, and feeds the {@link RateLimitSnapshot} SPI the
 * Outline admin page renders.
 *
 * <h2>Outline reports a budget ONLY on a 429</h2>
 * <p>Outline's rate-limiter middleware sets {@code Retry-After}, {@code RateLimit-Limit},
 * {@code RateLimit-Remaining} and {@code RateLimit-Reset} <b>exclusively inside its 429 catch block</b>
 * ({@code server/middlewares/rateLimiter.ts}); successful responses carry none of them. Two consequences
 * shape this class:
 *
 * <ul>
 *   <li>A healthy Outline connection can never show a live gauge. "Not reported" is the <em>normal</em>
 *       steady state, not a degraded one — and when the operator sets {@code RATE_LIMITER_ENABLED=false}
 *       it is the permanent one. This tracker therefore records <em>throttle events</em>, not a budget.</li>
 *   <li>What a 429 reports ({@code remaining ≈ 0}) is true only for the instant it was reported. The
 *       shared window-expiry rule in {@link RateLimitSnapshot#observed} retires it once the window lapses,
 *       so an observed exhaustion cannot freeze on the admin page as if it were current.</li>
 * </ul>
 *
 * <p>{@code Retry-After} — not {@code RateLimit-Reset} — is the reliable reset signal. Outline emits it as
 * {@code msBeforeNext / 1000}, i.e. possibly fractional seconds, and emits {@code RateLimit-Reset} as a
 * JavaScript {@code Date.toString()} (e.g. {@code "Sat Jul 18 2026 10:15:00 GMT+0000 (Coordinated
 * Universal Time)"}), which is neither epoch-seconds nor ISO-8601. We therefore parse {@code Retry-After}
 * as a decimal, round it up, and derive the window end from it; {@code RateLimit-Reset} is ignored.
 *
 * <h2>Scope key</h2>
 * The Outline client is stateless with respect to workspace (its methods take {@code (serverUrl, token)}),
 * so the scope is the <em>normalized server origin</em> ({@code scheme://authority}) derived from the request
 * URI on the way out and from the connection's stored {@code serverUrl} at read time (see
 * {@link #scopeOf(String)}). Two workspaces pointing at the same Outline host therefore share one bucket;
 * that is an accepted approximation for a diagnostic, and matches Outline's own per-host rate window.
 *
 * <h2>Null-until-observed</h2>
 * State is created only once real headers arrive, so {@link #snapshot(String)} returns {@code null} until
 * Outline has actually throttled us since the last restart — and the UI omits the row entirely, which for
 * this vendor is the expected long-run display.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
@WorkspaceAgnostic(
        "System-wide rate-limit tracking — records Outline API limits per server origin across all workspaces")
public class OutlineRateLimitTracker {

    static final String HEADER_LIMIT = "RateLimit-Limit";
    static final String HEADER_REMAINING = "RateLimit-Remaining";
    static final String HEADER_RETRY_AFTER = "Retry-After";

    private static final String METRIC_PREFIX = "outline.api.ratelimit";

    /** Inactive scope state is evicted after this idle period to bound memory. */
    private static final Duration EVICTION_THRESHOLD = Duration.ofHours(24);

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, ScopeState> stateByScope = new ConcurrentHashMap<>();

    public OutlineRateLimitTracker(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Normalizes a server URL (or full request URI) to its origin scope key {@code scheme://authority}.
     * Falls back to the trimmed input when the URL cannot be parsed, so a malformed value never crashes the
     * exchange filter — it simply keys its own bucket.
     */
    @Nullable
    public static String scopeOf(@Nullable String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        try {
            URI uri = URI.create(trimmed);
            if (uri.getScheme() == null || uri.getAuthority() == null) {
                return trimmed.toLowerCase(Locale.ROOT);
            }
            return (uri.getScheme() + "://" + uri.getAuthority()).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Records what a response actually reported. A no-op unless at least one rate-limit header is present,
     * which — given Outline only emits them on a 429 — means this creates state only for throttle events.
     *
     * <p>{@code Retry-After} is treated as the authoritative window end: it is the one value Outline emits
     * that we can parse reliably, and its presence is what tells us the vendor asked us to back off.
     */
    public void updateFromHeaders(@Nullable String scope, @Nullable HttpHeaders headers) {
        if (scope == null || headers == null) {
            return;
        }
        String limitStr = headers.getFirst(HEADER_LIMIT);
        String remainingStr = headers.getFirst(HEADER_REMAINING);
        String retryAfterStr = headers.getFirst(HEADER_RETRY_AFTER);
        if (limitStr == null && remainingStr == null && retryAfterStr == null) {
            return; // a normal, un-throttled Outline response — nothing was reported, so record nothing
        }
        Integer limit;
        Integer remaining;
        try {
            // Parse before touching state so a garbled header cannot leave a half-written observation.
            limit = limitStr == null ? null : Integer.valueOf(limitStr.trim());
            remaining = remainingStr == null ? null : Integer.valueOf(remainingStr.trim());
        } catch (NumberFormatException e) {
            log.debug("outline.ratelimit: could not parse rate-limit headers for scope {}: {}", scope, e.getMessage());
            return;
        }
        Instant observedAt = Instant.now();
        Duration retryAfter = parseRetryAfter(retryAfterStr);

        ScopeState state = getOrCreateState(scope);
        if (limit != null) {
            state.limit.set(limit);
        }
        if (remaining != null) {
            state.remaining.set(remaining);
        }
        if (retryAfter != null) {
            Instant throttledUntil = observedAt.plus(retryAfter);
            state.throttledUntil.set(throttledUntil);
            // The window Outline told us to wait out IS the reset instant. RateLimit-Reset is deliberately
            // ignored: Outline sends it as a JS Date.toString(), which no numeric or ISO parse accepts.
            state.resetAt.set(throttledUntil);
        }
        state.lastUpdated.set(observedAt);
        state.throttleCount.incrementAndGet();
        log.warn(
                "outline.ratelimit: throttled by Outline: scope={}, remaining={}, limit={}, retryAfter={}",
                scope,
                remaining,
                limit,
                retryAfter);
    }

    /**
     * Point-in-time observation for the sync-observability provider, or {@code null} if Outline has never
     * reported anything for this scope since the last restart (state is only created by
     * {@link #updateFromHeaders}, so {@code null} already means "never observed").
     *
     * <p>{@code limit} — the instance's configured per-window budget — survives window rollover, since a
     * ceiling is window-invariant; the observed {@code remaining} does not. That retirement is applied
     * centrally by {@link RateLimitSnapshot#observed}.
     */
    @Nullable
    public RateLimitSnapshot snapshot(@Nullable String scope) {
        if (scope == null) {
            return null;
        }
        ScopeState state = stateByScope.get(scope);
        if (state == null) {
            return null;
        }
        Instant observedAt = state.lastUpdated.get();
        if (observedAt == null) {
            return null;
        }
        return RateLimitSnapshot.observed(
                state.limit.get(), state.remaining.get(), state.resetAt.get(), observedAt, state.throttledUntil.get());
    }

    /** Number of tracked scopes (monitoring/debugging). */
    public int getTrackedScopeCount() {
        return stateByScope.size();
    }

    /**
     * Interprets Outline's {@code Retry-After}, which the middleware emits as {@code msBeforeNext / 1000}
     * — i.e. plain decimal seconds that are frequently <b>fractional</b> ({@code "1.5"}). A whole-number
     * parse would reject exactly the values Outline actually sends, so we parse a decimal and round up:
     * waiting a fraction of a second too long is harmless, waiting too little earns another 429.
     * Unparseable or negative → {@code null}.
     */
    @Nullable
    private static Duration parseRetryAfter(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal seconds = new BigDecimal(raw.trim());
            if (seconds.signum() < 0) {
                return null;
            }
            return Duration.ofSeconds(seconds.setScale(0, RoundingMode.CEILING).longValueExact());
        } catch (ArithmeticException | NumberFormatException e) {
            log.debug("outline.ratelimit: could not parse Retry-After value '{}'", raw);
            return null;
        }
    }

    @Scheduled(fixedRate = 3_600_000) // 1 hour
    void evictStaleEntries() {
        Instant threshold = Instant.now().minus(EVICTION_THRESHOLD);
        List<String> toEvict = new ArrayList<>();
        for (Map.Entry<String, ScopeState> entry : stateByScope.entrySet()) {
            Instant lastUpdated = entry.getValue().lastUpdated.get();
            if (lastUpdated != null && lastUpdated.isBefore(threshold)) {
                toEvict.add(entry.getKey());
            }
        }
        for (String scope : toEvict) {
            stateByScope.remove(scope);
            deregisterMetrics(scope);
            log.info("outline.ratelimit: evicted stale scope state: scope={}", scope);
        }
    }

    private ScopeState getOrCreateState(String scope) {
        return stateByScope.computeIfAbsent(scope, key -> {
            ScopeState state = new ScopeState();
            registerMetrics(key, state);
            return state;
        });
    }

    private void registerMetrics(String scope, ScopeState state) {
        Tags tags = Tags.of("scope", scope);
        // NaN, not 0, while unobserved: a gauge is a measurement too, and 0 would read as "exhausted".
        Gauge.builder(OutlineMetrics.OUTLINE_API_RATELIMIT_REMAINING, state, s -> asDouble(s.remaining.get()))
                .tags(tags)
                .description("Outline API rate-limit requests remaining, as reported by the last 429")
                .register(meterRegistry);
        Gauge.builder(OutlineMetrics.OUTLINE_API_RATELIMIT_LIMIT, state, s -> asDouble(s.limit.get()))
                .tags(tags)
                .description("Outline API rate-limit requests allowed per window, as reported by the last 429")
                .register(meterRegistry);
        Gauge.builder(OutlineMetrics.OUTLINE_API_RATELIMIT_SECONDS_UNTIL_RESET, state, s -> {
                    Instant reset = s.resetAt.get();
                    if (reset == null) {
                        return 0.0;
                    }
                    return Math.max(0, Duration.between(Instant.now(), reset).toSeconds());
                })
                .tags(tags)
                .description("Seconds until the Outline API rate-limit window resets")
                .register(meterRegistry);
        Gauge.builder(OutlineMetrics.OUTLINE_API_RATELIMIT_THROTTLES, state, s -> s.throttleCount.doubleValue())
                .tags(tags)
                .description("Number of Outline API throttle (429) responses observed for this scope")
                .register(meterRegistry);
    }

    private void deregisterMetrics(String scope) {
        List<Meter.Id> toRemove = new ArrayList<>();
        meterRegistry.getMeters().forEach(meter -> {
            if (meter.getId().getName().startsWith(METRIC_PREFIX)
                    && scope.equals(meter.getId().getTag("scope"))) {
                toRemove.add(meter.getId());
            }
        });
        for (Meter.Id id : toRemove) {
            meterRegistry.remove(id);
        }
    }

    /** Renders an unobserved value as NaN rather than a number that would be read as a measurement. */
    private static double asDouble(@Nullable Integer value) {
        return value == null ? Double.NaN : value.doubleValue();
    }

    /**
     * Per-scope state; atomic fields for thread-safe concurrent updates from the exchange filter. Every
     * observable field starts <b>null</b>, never {@code 0}: a partial header set seeded with {@code 0}
     * renders "0 remaining of N", claiming an exhaustion that was never measured.
     */
    private static final class ScopeState {

        final AtomicReference<Integer> remaining = new AtomicReference<>();
        final AtomicReference<Integer> limit = new AtomicReference<>();
        final AtomicReference<Instant> resetAt = new AtomicReference<>();
        final AtomicReference<Instant> throttledUntil = new AtomicReference<>();
        final AtomicReference<Instant> lastUpdated = new AtomicReference<>();
        final AtomicInteger throttleCount = new AtomicInteger(0);
    }
}
