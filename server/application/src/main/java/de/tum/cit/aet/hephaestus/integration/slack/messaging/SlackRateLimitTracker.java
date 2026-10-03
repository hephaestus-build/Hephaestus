package de.tum.cit.aet.hephaestus.integration.slack.messaging;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.spi.RateLimitSnapshot;
import de.tum.cit.aet.hephaestus.integration.slack.metrics.SlackMetrics;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Records Slack throttle <em>events</em> per workspace. Unlike its GitHub/GitLab/Outline siblings this
 * tracker is deliberately <b>event-based, not gauge-based</b>.
 *
 * <h2>Why Slack can never report a quota</h2>
 * Slack rate-limits per API <em>method</em> per workspace per app, in tiers whose published numbers are
 * floors rather than budgets ("Tier 2: 20+ per minute", "Tier 3: 50+ per minute"). It sends <b>no
 * remaining/limit headers on successful responses</b> — there is simply nothing to build a
 * {@code remaining / limit} gauge from, and inventing one from the tier tables would violate the honesty
 * rule in {@link RateLimitSnapshot}. This class therefore populates only {@code observedAt} and
 * {@code throttledUntil}, and {@link #snapshot} returns {@code null} until Slack has actually thrown a 429.
 *
 * <h2>What IS observable</h2>
 * A 429 carries {@code Retry-After} in seconds, which yields a real "throttled until T" instant. The state
 * that most needs surfacing is the non-Marketplace clamp on {@code conversations.history} /
 * {@code conversations.replies} (1 request per minute since 2025-05-29), which answers with
 * {@code Retry-After: 60} — an admin currently has no way to see that their Slack sync is crawling for
 * that reason.
 *
 * <h2>Not a pacer</h2>
 * There is no {@code waitIfNeeded} here. Back-off already lives in
 * {@link SlackMessageService}'s {@code callHonoringRateLimit}; this class only
 * watches. Slack's synchronous SDK client never self-throttles (only the async client does), so that hand
 * -rolled catch block is the single point where every 429 is already recognized — and therefore the single
 * point that feeds this tracker.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true", matchIfMissing = false)
@WorkspaceAgnostic("System-wide throttle tracking — records Slack 429 observations per workspace")
public class SlackRateLimitTracker {

    private static final String METRIC_PREFIX = "slack.api.ratelimit";

    /** Inactive workspace state is evicted after this idle period to bound memory. */
    private static final Duration EVICTION_THRESHOLD = Duration.ofHours(24);

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<Long, WorkspaceThrottleState> stateByWorkspace = new ConcurrentHashMap<>();

    public SlackRateLimitTracker(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Records an observed 429. Called from the one place Slack throttles are already recognized.
     *
     * @param workspaceId     the workspace whose token was throttled
     * @param retryAfterMillis the {@code Retry-After} wait Slack asked for, in milliseconds
     */
    public void recordThrottle(long workspaceId, long retryAfterMillis) {
        if (retryAfterMillis < 0) {
            return;
        }
        Instant observedAt = Instant.now();
        WorkspaceThrottleState state = getOrCreateState(workspaceId);
        state.lastThrottledAt.set(observedAt);
        state.throttledUntil.set(observedAt.plusMillis(retryAfterMillis));
        state.throttleCount.incrementAndGet();
        log.debug(
                "slack.ratelimit: observed 429: workspaceId={}, retryAfterMs={}, throttledUntil={}",
                workspaceId,
                retryAfterMillis,
                state.throttledUntil.get());
    }

    /**
     * What Slack has told us about throttling this workspace, or {@code null} if it never has.
     *
     * <p>{@code limit} and {@code remaining} are always {@code null} — see the class javadoc. A caller that
     * ever sees a number in either field for Slack is looking at a bug.
     */
    @Nullable
    public RateLimitSnapshot snapshot(long workspaceId) {
        WorkspaceThrottleState state = stateByWorkspace.get(workspaceId);
        if (state == null) {
            return null;
        }
        Instant observedAt = state.lastThrottledAt.get();
        if (observedAt == null) {
            return null;
        }
        return RateLimitSnapshot.observed(null, null, null, observedAt, state.throttledUntil.get());
    }

    /** Number of workspaces with a recorded throttle (monitoring/debugging). */
    public int getTrackedWorkspaceCount() {
        return stateByWorkspace.size();
    }

    /** Evicts workspaces whose last throttle is old enough that keeping it costs more than it tells us. */
    @Scheduled(fixedRate = 3_600_000) // 1 hour
    public void evictStaleEntries() {
        Instant threshold = Instant.now().minus(EVICTION_THRESHOLD);
        List<Long> toEvict = new ArrayList<>();
        for (Map.Entry<Long, WorkspaceThrottleState> entry : stateByWorkspace.entrySet()) {
            Instant last = entry.getValue().lastThrottledAt.get();
            if (last != null && last.isBefore(threshold)) {
                toEvict.add(entry.getKey());
            }
        }
        for (Long workspaceId : toEvict) {
            stateByWorkspace.remove(workspaceId);
            deregisterMetrics(workspaceId);
            log.info("slack.ratelimit: evicted stale throttle state: workspaceId={}", workspaceId);
        }
    }

    private WorkspaceThrottleState getOrCreateState(long workspaceId) {
        return stateByWorkspace.computeIfAbsent(workspaceId, id -> {
            WorkspaceThrottleState state = new WorkspaceThrottleState();
            registerMetrics(id, state);
            return state;
        });
    }

    private void registerMetrics(long workspaceId, WorkspaceThrottleState state) {
        Tags tags = Tags.of("workspace_id", String.valueOf(workspaceId));
        Gauge.builder(SlackMetrics.SLACK_API_RATELIMIT_THROTTLES, state, s -> s.throttleCount.doubleValue())
                .tags(tags)
                .description("Number of Slack API throttle (429) responses observed for this workspace")
                .register(meterRegistry);
        Gauge.builder(SlackMetrics.SLACK_API_RATELIMIT_THROTTLED_UNTIL_SECONDS, state, s -> {
                    Instant until = s.throttledUntil.get();
                    if (until == null) {
                        return 0.0;
                    }
                    return Math.max(0, Duration.between(Instant.now(), until).toSeconds());
                })
                .tags(tags)
                .description("Seconds remaining on the last Slack Retry-After back-off for this workspace")
                .register(meterRegistry);
    }

    private void deregisterMetrics(long workspaceId) {
        String workspaceTag = String.valueOf(workspaceId);
        List<Meter.Id> toRemove = new ArrayList<>();
        meterRegistry.getMeters().forEach(meter -> {
            if (meter.getId().getName().startsWith(METRIC_PREFIX)
                    && workspaceTag.equals(meter.getId().getTag("workspace_id"))) {
                toRemove.add(meter.getId());
            }
        });
        for (Meter.Id id : toRemove) {
            meterRegistry.remove(id);
        }
    }

    /** Per-workspace throttle state. No budget fields exist here, because Slack reports no budget. */
    private static final class WorkspaceThrottleState {

        final AtomicReference<Instant> lastThrottledAt = new AtomicReference<>();
        final AtomicReference<Instant> throttledUntil = new AtomicReference<>();
        final AtomicInteger throttleCount = new AtomicInteger(0);
    }
}
