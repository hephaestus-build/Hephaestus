package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHRateLimit;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.client.ClientGraphQlResponse;

/**
 * SPI for tracking GitHub API rate limits per scope/installation.
 *
 * <p>GitHub rate limits are per-installation (GitHub App) or per-token (PAT),
 * not global. This interface provides scope-aware rate limit tracking to
 * ensure accurate throttling decisions in multi-workspace deployments.
 *
 * <p>Implementations must be thread-safe as multiple sync threads may
 * access the same scope's rate limit state concurrently.
 *
 * @see de.tum.cit.aet.hephaestus.integration.scm.github.common.ScopedRateLimitTracker
 */
public interface RateLimitTracker {
    /**
     * Updates rate limit state from a GraphQL response for a specific scope.
     *
     * @return the extracted rate limit info, or null if not present
     */
    @Nullable
    GHRateLimit updateFromResponse(Long scopeId, @Nullable ClientGraphQlResponse response);

    /**
     * Gets the remaining rate limit points to throttle against for a scope.
     *
     * <p>This is a <b>decision</b> API, not a reporting one. It falls back to a conservative assumed
     * ceiling when the scope has never been observed, and treats a closed window as "assume full budget"
     * so sync does not stall on stale data. Neither assumption may be displayed — see
     * {@link de.tum.cit.aet.hephaestus.integration.core.spi.RateLimitSnapshot} for the honesty rule that
     * governs the reporting path.
     *
     * @return remaining points, or the assumed value if unknown
     */
    int getRemaining(Long scopeId);

    /**
     * Falls back to the assumed ceiling when unobserved. A decision API — see {@link #getRemaining} on
     * why its fallback must never be displayed.
     *
     * @return total limit points per hour
     */
    int getLimit(Long scopeId);

    /** Critical means requests should stop immediately to avoid exhausting the limit entirely. */
    boolean isCritical(Long scopeId);

    /**
     * Blocks the calling thread until either the rate limit resets or the maximum wait duration
     * (5 minutes) is reached.
     *
     * @return true if waited, false if no wait was needed
     * @throws InterruptedException if interrupted while waiting
     */
    boolean waitIfNeeded(Long scopeId) throws InterruptedException;

    @Nullable
    Instant getResetAt(Long scopeId);
}
