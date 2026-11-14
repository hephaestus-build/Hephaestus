package de.tum.cit.aet.hephaestus.integration.slack.messaging;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The throttle budget of one Slack Web API operation: when the gateway waits for Slack's {@code Retry-After} after a
 * 429 and calls again, and when it gives up. The SDK's synchronous client surfaces a 429 as an exception and does
 * <em>not</em> retry it, so the gateway asks its budget after each 429 ({@link #awaitRetry}).
 *
 * <p>The budget counts the time since it was made, on the monotonic clock: the calls as well as the waits. A 429 is
 * reported to the tracker, and tried again only after its whole wait (the {@code Retry-After} with its jitter), and
 * only when that wait is at most the maximum wait and it and the reserve for the next call still fit in the budget.
 * Otherwise the 429 is surfaced at once: the call is never tried earlier than Slack asked, to fit a cap or the budget.
 * Thus no retry starts that the budget cannot hold, but each HTTP call keeps only its own deadline: a call already
 * running is not cut off when the budget ends.
 */
final class SlackBackoff {

    private static final Logger log = LoggerFactory.getLogger(SlackBackoff.class);

    /**
     * What one Web API call of the SDK may take: the requested call, and before it the {@code auth.test} with which
     * the SDK finds the team of a token that it has not seen (its team id cache), each within the gateway's HTTP call
     * deadline ({@link SlackMessageService#NATIVE_CALL_TIMEOUT_MS}).
     */
    private static final long NATIVE_OPERATION_RESERVE_MS = 2L * SlackMessageService.NATIVE_CALL_TIMEOUT_MS;

    /**
     * Total time of a single interactive call, the calls and the {@code Retry-After} waits together; a throttle beyond
     * this gives up rather than blocking a request/worker thread unboundedly. With {@link #NATIVE_OPERATION_RESERVE_MS}
     * kept for the next call, a wait of up to about 10 s fits.
     */
    private static final long RATE_LIMIT_TOTAL_BUDGET_MS = 30_000L;

    /**
     * Never wait longer than this for a single {@code Retry-After}: a longer one surfaces the 429 — defends against an
     * absurd/hostile header.
     */
    private static final long RATE_LIMIT_MAX_WAIT_MS = 20_000L;

    /**
     * Wider bounds for history reads that no one waits on: the nightly history reconciliation and the Hephaestus reply
     * reconciler's history read. Slack's clamp on {@code conversations.history}/{@code conversations.replies} for
     * distributed non-Marketplace apps (1 request per minute) answers with {@code Retry-After: 60}, which the
     * interactive {@link #RATE_LIMIT_MAX_WAIT_MS} cap would refuse.
     */
    private static final long SYNC_RATE_LIMIT_MAX_WAIT_MS = 90_000L;

    private static final long SYNC_RATE_LIMIT_TOTAL_BUDGET_MS = 150_000L;

    private final SlackRateLimitTracker tracker;
    private final long workspaceId;
    private final long maxWaitMs;
    private final long totalBudgetMs;
    private final long reserveMs;
    private final long startedNanos = System.nanoTime();
    private int attempt;

    /**
     * @param reserveMs what the next call may take; {@link #NATIVE_OPERATION_RESERVE_MS} for an SDK call
     */
    SlackBackoff(SlackRateLimitTracker tracker, long workspaceId, long maxWaitMs, long totalBudgetMs, long reserveMs) {
        this.tracker = tracker;
        this.workspaceId = workspaceId;
        this.maxWaitMs = maxWaitMs;
        this.totalBudgetMs = totalBudgetMs;
        this.reserveMs = reserveMs;
    }

    /** The budget of a call that someone waits on ({@link #RATE_LIMIT_TOTAL_BUDGET_MS}), from now. */
    static SlackBackoff interactive(SlackRateLimitTracker tracker, long workspaceId) {
        return new SlackBackoff(
                tracker, workspaceId, RATE_LIMIT_MAX_WAIT_MS, RATE_LIMIT_TOTAL_BUDGET_MS, NATIVE_OPERATION_RESERVE_MS);
    }

    /** The budget of a history read that no one waits on ({@link #SYNC_RATE_LIMIT_TOTAL_BUDGET_MS}), from now. */
    static SlackBackoff history(SlackRateLimitTracker tracker, long workspaceId) {
        return new SlackBackoff(
                tracker,
                workspaceId,
                SYNC_RATE_LIMIT_MAX_WAIT_MS,
                SYNC_RATE_LIMIT_TOTAL_BUDGET_MS,
                NATIVE_OPERATION_RESERVE_MS);
    }

    /**
     * After a 429 that asked for {@code retryAfterMs}: reports the throttle, then waits the whole wait when the budget
     * admits it.
     *
     * @return {@code true} when the call may be tried again; {@code false} when the budget refuses the wait, or the
     *     thread was interrupted while it waited (the interrupt flag stays set for the caller)
     */
    boolean awaitRetry(long retryAfterMs) {
        // Report the throttle before deciding whether to retry: an admin needs to see a 429 even
        // (especially) when the retry budget is spent and the call is about to fail.
        tracker.recordThrottle(workspaceId, retryAfterMs);
        if (retryAfterMs > maxWaitMs) {
            // Slack's own wait is already beyond the cap. Refused before the jitter and the reserve are added, so a
            // huge header cannot overflow into a short wait.
            return false;
        }
        long waitMs = withJitter(retryAfterMs, ++attempt);
        long budgetLeftMs = totalBudgetMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        if (waitMs > maxWaitMs || waitMs + reserveMs > budgetLeftMs) {
            // Slack's wait is beyond the cap, or it and the next call do not fit in what is left.
            return false;
        }
        log.warn(
                "Slack rate-limited (429); backing off {} ms before retry (attempt {}, budget left {} ms)",
                waitMs,
                attempt,
                budgetLeftMs);
        sleep(waitMs);
        // Interrupted mid-backoff (e.g. a shutting-down worker thread): stop retrying immediately rather than spinning
        // the rest of the budget in a tight loop.
        return !Thread.currentThread().isInterrupted();
    }

    /**
     * The {@code Retry-After} wait in ms of a response with HTTP {@code status}, if it is a 429, else
     * {@link SlackSendException#NOT_RATE_LIMITED}. Slack always sends {@code Retry-After} on a 429; a missing/garbled
     * header defaults to 1s. A huge one, also digits beyond a {@code long}, saturates at {@link Long#MAX_VALUE} rather
     * than wrapping to a negative wait or falling back to the default.
     */
    static long retryAfterMillis(int status, @Nullable String header) {
        if (status != 429) {
            return SlackSendException.NOT_RATE_LIMITED;
        }
        long seconds = 1L;
        if (header != null) {
            String value = header.trim();
            try {
                seconds = Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                // Digits alone that do not fit a long ask for longer than anything; anything else keeps the 1s default.
                if (!value.isEmpty() && value.chars().allMatch(c -> c >= '0' && c <= '9')) {
                    seconds = Long.MAX_VALUE;
                }
            }
        }
        return TimeUnit.SECONDS.toMillis(Math.max(0L, seconds));
    }

    /**
     * Honor {@code retryAfterMs} plus a small jitter; the caller refuses the result beyond its maximum wait. When the
     * header asked for no wait, fall back to a mild exponential backoff. Jitter avoids a thundering herd of replicas
     * retrying in lockstep.
     */
    private static long withJitter(long retryAfterMs, int attempt) {
        long base = retryAfterMs > 0 ? retryAfterMs : 1000L * (1L << Math.min(attempt - 1, 4));
        return base + ThreadLocalRandom.current().nextLong(0L, 250L);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
