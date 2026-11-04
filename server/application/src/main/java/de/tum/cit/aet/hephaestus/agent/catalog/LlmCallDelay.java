package de.tum.cit.aet.hephaestus.agent.catalog;

import org.jspecify.annotations.Nullable;

/**
 * What a pool knows from time to first token. A model server on one's own GPUs mostly queues instead of refusing, so
 * {@link LlmCallLimit} would keep rising while calls wait longer. A queue delays every call behind it, also the shortest,
 * while a long prompt delays only itself. So a pool compares the fastest first token of a window of calls with the
 * fastest it ever saw from its provider, and lowers its limit when even the fastest waited.
 *
 * <p>Total call time would measure the length of the answer. Tokens per second of a stream fall while a batch fills,
 * when more calls still add throughput, and then stop falling at the knee, because the batch cannot grow.
 *
 * @param baselineMillis the fastest first token known; {@code null} before the first window
 * @param windowMillis the fastest first token of the current window
 * @param samples the first tokens of the current window
 */
public record LlmCallDelay(
        @Nullable Integer baselineMillis, @Nullable Integer windowMillis, int samples) {

    /** Below this, one slow call would decide a window: short prompts and queues show only across several calls. */
    private static final int SMALLEST_WINDOW = 4;

    /** What one first token decides. A {@code keep} below 1 is the share of the limit to keep. */
    public record Step(LlmCallDelay delay, double keep) {}

    public Step afterFirstToken(int millis, int limit) {
        int fastest = windowMillis == null ? millis : Math.min(windowMillis, millis);
        if (samples + 1 < Math.max(SMALLEST_WINDOW, limit / 2))
            return new Step(new LlmCallDelay(baselineMillis, fastest, samples + 1), 1);
        int baseline = baselineMillis == null ? fastest : Math.min(baselineMillis, fastest);
        int allowed = allowed(baseline);
        if (fastest <= allowed) return new Step(new LlmCallDelay(baseline, null, 0), 1);
        // At the floor, these calls alone cannot make the queue, so the baseline no longer describes this provider.
        if (limit <= LlmCallLimit.FLOOR) return new Step(new LlmCallDelay(fastest, null, 0), 1);
        return new Step(new LlmCallDelay(baseline, null, 0), (double) allowed / fastest);
    }

    /** Whether this first token waited in the provider's queue, so that its call says nothing for a higher limit. */
    public boolean queued(int millis) {
        return baselineMillis != null && millis > allowed(baselineMillis);
    }

    /** Twice the baseline, as a queue adds a multiple of it, plus a second, as a fast provider's jitter is a multiple too. */
    private static int allowed(int baseline) {
        return baseline * 2 + 1000;
    }
}
