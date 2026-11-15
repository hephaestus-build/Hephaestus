package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import org.jspecify.annotations.Nullable;

/**
 * A failed model call that the provider did not answer, or answered only with a rate limit. Neither says anything about
 * the work that made the call, so the attempt that met it spends no retry. Every other failure is an answer: a refusal
 * of the proxy, an error that the provider answered, or a stream that the provider started and did not finish.
 */
public enum ProviderOutage {
    /** No answer arrived, the connection's breaker paused calls, or the provider answered 5xx or 408. */
    UNREACHABLE("provider_unreachable"),
    /** The provider answered 429. */
    RATE_LIMITED("rate_limited");

    private static final int REQUEST_TIMEOUT = 408;
    private static final int TOO_MANY_REQUESTS = 429;
    private static final int SERVER_ERROR = 500;

    private final String tag;

    ProviderOutage(String tag) {
        this.tag = tag;
    }

    /** The metric tag value of the requeue that the outage causes. */
    public String tag() {
        return tag;
    }

    /** The outage that {@code failure} shows; null when the provider or the proxy answered the call. */
    public static @Nullable ProviderOutage of(MentorTurn.FailedCall failure) {
        Integer status = failure.status();
        return switch (failure.kind()) {
            case LlmProxyService.UPSTREAM_STALLED, LlmProxyService.UPSTREAM_PAUSED -> UNREACHABLE;
            case LlmProxyService.UPSTREAM_STATUS -> {
                if (status == null) yield null;
                if (status == TOO_MANY_REQUESTS) yield RATE_LIMITED;
                yield status == REQUEST_TIMEOUT || status >= SERVER_ERROR ? UNREACHABLE : null;
            }
            case LlmProxyService.GATEWAY_ERROR -> null;
            // A call that failed before any answer is named by its exception's class and has no status. A refusal and
            // a stream's outcome always carry the status that was answered.
            default -> status == null ? UNREACHABLE : null;
        };
    }
}
