package de.tum.cit.aet.hephaestus.integration.outline.client;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * Raised when a call to the Outline API fails — an unreachable host, a rejected token, or an
 * unexpected response. Carries a human-readable reason suitable for surfacing to the admin who
 * is connecting the integration.
 *
 * <p>{@link #isRetryable()} distinguishes transient failures (5xx, a transport error, a 429 via the
 * {@link OutlineRateLimitedException} subtype) from permanent ones (4xx, an open circuit) so the
 * {@code outlineRestApiRetry} decorator retries the former with backoff and gives up on the latter.
 */
public class OutlineApiException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final boolean retryable;

    public OutlineApiException(String message) {
        super(message);
        this.retryable = false;
    }

    public OutlineApiException(String message, @Nullable Throwable cause) {
        this(message, cause, false);
    }

    public OutlineApiException(String message, @Nullable Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
