package de.tum.cit.aet.hephaestus.integration.slack.messaging;

import java.util.concurrent.ThreadLocalRandom;

/** How long the Slack gateway waits before it repeats a call that Slack throttled. */
final class SlackBackoff {

    private SlackBackoff() {}

    /**
     * Honor {@code retryAfterMs} plus a small jitter; the caller caps the result at its maximum wait. When the header
     * asked for no wait, fall back to a mild exponential backoff. Jitter avoids a thundering
     * herd of replicas retrying in lockstep.
     */
    static long withJitter(long retryAfterMs, int attempt) {
        long base = retryAfterMs > 0 ? retryAfterMs : 1000L * (1L << Math.min(attempt - 1, 4));
        return base + ThreadLocalRandom.current().nextLong(0L, 250L);
    }

    static void sleep(long millis) {
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
