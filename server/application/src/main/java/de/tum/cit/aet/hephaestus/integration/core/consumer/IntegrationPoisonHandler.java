package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.handler.PayloadParsingException;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.Message;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** Retries operational failures; permanent or exhausted unknown failures require durable capture before TERM. */
@Component
@ConditionalOnServerRole
public class IntegrationPoisonHandler {

    private static final Logger log = LoggerFactory.getLogger(IntegrationPoisonHandler.class);
    private static final String UNKNOWN_KIND_TAG = "unknown";

    /** Prevent shift and multiplication overflow during a long outage. */
    private static final int MAX_EXPONENT = 30;

    private final NatsConsumerProperties properties;
    private final MeterRegistry meterRegistry;
    private final ConsumerDeadLetterService deadLetters;
    private final Map<String, Counter> nakCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> poisonCounters = new ConcurrentHashMap<>();

    public IntegrationPoisonHandler(
            NatsConsumerProperties properties, MeterRegistry meterRegistry, ConsumerDeadLetterService deadLetters) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.deadLetters = deadLetters;
    }

    /**
     * NAKs a transient or unclassified failure without a delivery-count limit.
     *
     * <p>Designed to be a no-throw call from the consumer's catch-all error path: on any
     * failure to NAK (e.g. closed connection during shutdown) we fall back to an
     * unconditional {@link Message#nak()} and then to silence. The message will be
     * redelivered after the JetStream ack-wait if all NAK strategies fail.
     */
    public void nakWithBackoff(@Nullable Message msg) {
        if (msg == null) {
            return;
        }
        long deliveredCount = readDeliveredCount(msg);

        // deliveredCount is 1-based — first attempt is 1, so use (count - 1) as exponent.
        int attempt = (int) Math.min(deliveredCount - 1, Integer.MAX_VALUE);
        long delayMs = exponentialBackoffMillis(
                attempt,
                properties.poison().baseDelay().toMillis(),
                properties.poison().maxDelay().toMillis());

        nakCounter(msg).increment();
        try {
            log.debug(
                    "NAKing message with backoff: route={}, attempt={}, delayMs={}",
                    ConsumerSubjectMath.routeLabel(msg.getSubject()),
                    deliveredCount,
                    delayMs);
            msg.nakWithDelay(Duration.ofMillis(delayMs));
        } catch (Exception nakWithDelayFailed) {
            // Closed connection / shutdown / etc. — fall back to immediate NAK so the
            // server-side ack-wait timeout becomes the only thing the message is waiting on.
            log.debug(
                    "Failed to NAK with delay, using immediate NAK: error={}",
                    nakWithDelayFailed.getClass().getSimpleName());
            try {
                msg.nak();
            } catch (Exception immediateNakFailed) {
                // Message will be redelivered after ack timeout — nothing more we can do.
                log.trace(
                        "Failed to NAK message: error={}",
                        immediateNakFailed.getClass().getSimpleName());
            }
        }
    }

    public void handleFailure(@Nullable Long workspaceId, Message msg, Throwable failure) {
        FailureKind kind = classify(failure);
        if (kind == FailureKind.PERMANENT
                || (kind == FailureKind.UNKNOWN
                        && readDeliveredCount(msg) >= properties.poison().maxRedeliver())) {
            try {
                deadLetters.capture(workspaceId, msg, failure);
                msg.term();
                poisonCounter(msg).increment();
                return;
            } catch (Exception captureOrTermFailure) {
                log.warn(
                        "Dead-letter capture or termination failed: error.type={}",
                        captureOrTermFailure.getClass().getName());
            }
        }
        nakWithBackoff(msg);
    }

    private enum FailureKind {
        PERMANENT,
        TRANSIENT,
        UNKNOWN
    }

    private static FailureKind classify(Throwable failure) {
        boolean transientFailure = false;
        Set<Throwable> seen = new HashSet<>();
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof PayloadParsingException) return FailureKind.PERMANENT;
            if (cause instanceof IOException
                    || cause instanceof TransientDataAccessException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof WebClientRequestException) {
                transientFailure = true;
            }
            if (cause instanceof WebClientResponseException response) {
                transientFailure |= isTransientResponse(response.getStatusCode(), response.getHeaders());
            }
            if (cause instanceof RestClientResponseException response) {
                transientFailure |= isTransientResponse(response.getStatusCode(), response.getResponseHeaders());
            }
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                transientFailure |= state != null
                        && (state.startsWith("08")
                                || Set.of("40001", "40P01", "53300", "55P03", "57P01", "57P02", "57P03")
                                        .contains(state));
            }
        }
        return transientFailure ? FailureKind.TRANSIENT : FailureKind.UNKNOWN;
    }

    private static boolean isTransientResponse(HttpStatusCode status, @Nullable HttpHeaders headers) {
        return status.is5xxServerError()
                || status.value() == 429
                || status.value() == 408
                || (status.value() == 403
                        && headers != null
                        && (headers.containsHeader(HttpHeaders.RETRY_AFTER)
                                || "0".equals(headers.getFirst("X-RateLimit-Remaining"))));
    }

    static long readDeliveredCount(Message msg) {
        try {
            var metadata = msg.metaData();
            return metadata != null ? metadata.deliveredCount() : 1L;
        } catch (Exception e) {
            // Non-JetStream message or detached metadata — treat as first delivery.
            return 1L;
        }
    }

    private Counter nakCounter(Message msg) {
        String kindTag = kindTag(msg);
        return nakCounters.computeIfAbsent(
                kindTag,
                k -> Counter.builder(IntegrationCoreMetrics.INTEGRATION_CONSUMER_NAK)
                        .tag("kind", k)
                        .register(meterRegistry));
    }

    private Counter poisonCounter(Message msg) {
        String kindTag = kindTag(msg);
        return poisonCounters.computeIfAbsent(
                kindTag,
                k -> Counter.builder(IntegrationCoreMetrics.INTEGRATION_CONSUMER_POISON)
                        .tag("kind", k)
                        .register(meterRegistry));
    }

    /** Equal jitter keeps retries dispersed and bounds the minimum delay during a storm. */
    static long exponentialBackoffMillis(int attempt, long baseMs, long maxMs) {
        int clampedExponent = Math.max(0, Math.min(attempt, MAX_EXPONENT));
        long cap = baseMs > (maxMs >> clampedExponent) ? maxMs : baseMs * (1L << clampedExponent);
        return ThreadLocalRandom.current().nextLong(Math.max(1, cap / 2), Math.max(2, cap + 1));
    }

    private static String kindTag(@Nullable Message msg) {
        if (msg == null) {
            return UNKNOWN_KIND_TAG;
        }
        String subject = msg.getSubject();
        return ConsumerSubjectMath.kindFromSubjectPrefix(subject)
                .map(k -> k.name().toLowerCase(Locale.ROOT))
                .orElse(UNKNOWN_KIND_TAG);
    }
}
