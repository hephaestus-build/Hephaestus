package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.handler.PayloadParsingException;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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

/**
 * Decides what a failed event does next. A proven operational failure retries with backoff until it recovers; a
 * payload that does not parse, or an unknown failure that used its attempts, becomes a dead letter that keeps its
 * payload for a replay. The event stays at the head of its scope while it waits, so its scope keeps its order.
 */
@Component
@ConditionalOnServerRole
public class IntegrationPoisonHandler {

    private static final Logger log = LoggerFactory.getLogger(IntegrationPoisonHandler.class);
    private static final String UNKNOWN_KIND_TAG = "unknown";

    /** Prevent shift and multiplication overflow during a long outage. */
    private static final int MAX_EXPONENT = 30;

    private final ProviderEventConsumerProperties properties;
    private final MeterRegistry meterRegistry;
    private final ProviderEventStore store;
    private final Map<String, Counter> retryCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> poisonCounters = new ConcurrentHashMap<>();

    public IntegrationPoisonHandler(
            ProviderEventConsumerProperties properties, MeterRegistry meterRegistry, ProviderEventStore store) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.store = store;
    }

    /** Records the outcome of a failed attempt; a lease that was taken back meanwhile records nothing. */
    public void handleFailure(ProviderEventStore.Claimed claimed, Throwable failure) {
        String subject = claimed.event().subject();
        FailureKind kind = classify(failure);
        if (kind == FailureKind.PERMANENT
                || (kind == FailureKind.UNKNOWN
                        && claimed.failures() + 1 >= properties.poison().maxAttempts())) {
            Throwable diagnostic = diagnosticCause(failure);
            if (store.dead(
                    claimed.id(), claimed.leaseEpoch(), diagnostic.getClass().getName())) {
                log.atError()
                        .addKeyValue("event.name", "integration.message.captured")
                        .addKeyValue("integration.route", ConsumerSubjectMath.routeLabel(subject))
                        .addKeyValue("integration.scope.id", claimed.workspaceId())
                        .addKeyValue("integration.delivery", claimed.attempt())
                        .addKeyValue("error.type", failure.getClass().getName())
                        .addKeyValue("error.root_type", diagnostic.getClass().getName())
                        .addKeyValue(
                                "error.stack_trace",
                                Arrays.stream(diagnostic.getStackTrace())
                                        .limit(32)
                                        .map(StackTraceElement::toString)
                                        .toList())
                        .log("Failed event stored for replay");
                poisonCounter(subject).increment();
            }
            return;
        }
        retry(claimed, kind == FailureKind.UNKNOWN);
    }

    /**
     * Returns the event to its scope's head after a backoff that grows with its attempts. An operational failure
     * does not spend the poison budget: it retries without a limit.
     */
    public void retry(ProviderEventStore.Claimed claimed, boolean counted) {
        // The attempt is 1-based, so the first retry waits the base delay.
        long delayMs = exponentialBackoffMillis(
                claimed.attempt() - 1,
                properties.poison().baseDelay().toMillis(),
                properties.poison().maxDelay().toMillis());
        if (store.retry(claimed.id(), claimed.leaseEpoch(), Duration.ofMillis(delayMs), counted)) {
            retryCounter(claimed.event().subject()).increment();
        }
    }

    static Throwable diagnosticCause(Throwable failure) {
        Throwable diagnostic = failure;
        // Bound traversal even when a failure has a cyclic or unusually deep cause chain.
        for (int depth = 0; depth < 16 && diagnostic.getCause() != null; depth++) {
            diagnostic = Objects.requireNonNull(diagnostic.getCause());
        }
        return diagnostic;
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
            if (cause instanceof SocketException
                    || cause instanceof SocketTimeoutException
                    || cause instanceof UnknownHostException
                    || cause instanceof SQLTransientException
                    || cause instanceof SQLRecoverableException
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

    private Counter retryCounter(String subject) {
        String kindTag = kindTag(subject);
        return retryCounters.computeIfAbsent(
                kindTag,
                k -> Counter.builder(IntegrationCoreMetrics.INTEGRATION_CONSUMER_RETRY)
                        .tag("kind", k)
                        .register(meterRegistry));
    }

    private Counter poisonCounter(String subject) {
        String kindTag = kindTag(subject);
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

    static String kindTag(@Nullable String subject) {
        return ConsumerSubjectMath.kindFromSubjectPrefix(subject)
                .map(k -> k.name().toLowerCase(Locale.ROOT))
                .orElse(UNKNOWN_KIND_TAG);
    }
}
