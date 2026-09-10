package de.tum.cit.aet.hephaestus.integration.outline.client;

import java.io.Serial;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Raised when Outline answers a request with HTTP 429. Distinct from a plain {@link OutlineApiException}
 * so the sync cycle can recognise throttling and pause rather than treat it as a hard failure — the
 * cycle logs and resumes next tick instead of aborting.
 *
 * <p>{@link #getRetryAfter()} carries the server's {@code Retry-After} hint when one was provided
 * (seconds), or {@code null} when the header was absent or unparseable. It is retryable: the
 * {@code outlineRestApiRetry} decorator honors this hint as its backoff interval.
 */
public class OutlineRateLimitedException extends OutlineApiException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final @Nullable Duration retryAfter;

    public OutlineRateLimitedException(@Nullable Duration retryAfter, @Nullable Throwable cause) {
        super("Outline rate-limited the request (HTTP 429)", cause, /* retryable */ true);
        this.retryAfter = retryAfter;
    }

    public @Nullable Duration getRetryAfter() {
        return retryAfter;
    }
}
