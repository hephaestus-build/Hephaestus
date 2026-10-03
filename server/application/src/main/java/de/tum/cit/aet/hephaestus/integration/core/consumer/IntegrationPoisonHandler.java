package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.Message;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * NAK-with-exponential-backoff and poison-message handler shared across every NATS
 * consumer in the integration framework.
 *
 * <p>Policy: read {@code deliveredCount()} from message metadata; at
 * {@code deliveredCount >= maxRedeliver} ACK and bump {@code integration.consumer.poison}
 * (ERROR log — operator-actionable); otherwise NAK with delay
 * {@code clamp(base * 2^attempt + jitter, maxDelay)} and bump
 * {@code integration.consumer.nak}.
 *
 * <p>Counters tag {@code kind=<integration-kind>} derived from
 * {@link ConsumerSubjectMath#kindFromSubjectPrefix}; cache is per-kind. Backoff math is
 * inlined (rather than imported from {@code integration.scm.github.common.ExponentialBackoff})
 * to keep this package free of a {@code integration.scm} import.
 */
@Component
public class IntegrationPoisonHandler {

    private static final Logger log = LoggerFactory.getLogger(IntegrationPoisonHandler.class);
    private static final String UNKNOWN_KIND_TAG = "unknown";

    /**
     * Random jitter ceiling added to each NAK backoff. Kept at 1s — large enough to
     * de-cluster simultaneous retries from sibling consumers, small relative to the
     * minimum 2s base delay so it never inverts the exponential ramp.
     */
    private static final long JITTER_MS = 1_000L;

    /**
     * Cap on the exponent in {@code 1L << attempt} — at attempt=6 the multiplier is 64x,
     * which already saturates the typical max-delay knob (5m / 2s = 150x base). Caps
     * prevent {@code long} overflow on the unlikely runaway redelivery loop.
     */
    private static final int MAX_EXPONENT = 6;

    private final NatsConsumerProperties properties;
    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> nakCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> poisonCounters = new ConcurrentHashMap<>();

    public IntegrationPoisonHandler(NatsConsumerProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * NAKs the message with exponential backoff, or ACKs it as poison if the redelivery
     * count has hit {@link NatsConsumerProperties.PoisonProperties#maxRedeliver()}.
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

        if (deliveredCount >= properties.poison().maxRedeliver()) {
            ackPoison(msg, "redelivery limit exceeded");
            return;
        }

        // deliveredCount is 1-based — first attempt is 1, so use (count - 1) as exponent.
        int attempt = (int) Math.min(deliveredCount - 1, Integer.MAX_VALUE);
        long delayMs = exponentialBackoffMillis(
                attempt,
                properties.poison().baseDelay().toMillis(),
                properties.poison().maxDelay().toMillis());

        nakCounter(msg).increment();
        try {
            log.debug(
                    "NAKing message with backoff: subject={}, attempt={}, delayMs={}",
                    sanitizeForLog(msg.getSubject()),
                    deliveredCount,
                    delayMs);
            msg.nakWithDelay(Duration.ofMillis(delayMs));
        } catch (Exception nakWithDelayFailed) {
            // Closed connection / shutdown / etc. — fall back to immediate NAK so the
            // server-side ack-wait timeout becomes the only thing the message is waiting on.
            log.debug("Failed to NAK with delay, using immediate NAK: error={}", nakWithDelayFailed.getMessage());
            try {
                msg.nak();
            } catch (Exception immediateNakFailed) {
                // Message will be redelivered after ack timeout — nothing more we can do.
                log.trace("Failed to NAK message: error={}", immediateNakFailed.getMessage());
            }
        }
    }

    /**
     * @return whether the message has exhausted its redelivery budget. Caller may use this
     *     for short-circuit reporting; {@link #nakWithBackoff(Message)} performs the same
     *     check internally so callers don't have to.
     */
    public boolean isPoison(Message msg) {
        if (msg == null) {
            return false;
        }
        return readDeliveredCount(msg) >= properties.poison().maxRedeliver();
    }

    /**
     * Acknowledges a poison message — terminal action that removes it from the redelivery
     * loop. Logged at ERROR with the structured reason so it surfaces in operator
     * dashboards. The counter is bumped under the message's kind tag.
     */
    public void ackPoison(Message msg, String reason) {
        if (msg == null) {
            return;
        }
        long deliveredCount = readDeliveredCount(msg);
        long streamSeq = readStreamSequence(msg);
        poisonCounter(msg).increment();
        log.error(
                "Poison message detected, ACKing to break redelivery loop: subject={}, deliveredCount={}, streamSeq={}, reason={}",
                sanitizeForLog(msg.getSubject()),
                deliveredCount,
                streamSeq,
                sanitizeForLog(reason));
        try {
            msg.ack();
        } catch (Exception ackFailed) {
            // ACK failure is non-fatal; the message will redeliver and we'll get another shot.
            log.debug("Failed to ACK poison message: error={}", ackFailed.getMessage());
        }
    }

    private long readDeliveredCount(Message msg) {
        try {
            var metadata = msg.metaData();
            return metadata != null ? metadata.deliveredCount() : 1L;
        } catch (Exception e) {
            // Non-JetStream message or detached metadata — treat as first delivery.
            return 1L;
        }
    }

    private long readStreamSequence(Message msg) {
        try {
            var metadata = msg.metaData();
            return metadata != null ? metadata.streamSequence() : -1L;
        } catch (Exception e) {
            return -1L;
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

    /**
     * Full-jitter exponential backoff: {@code clamp(base * 2^attempt + random(0, JITTER_MS), maxMs)}.
     *
     * <p>Package-private so {@link IntegrationPoisonHandlerTest} can exercise the math
     * directly. The implementation mirrors the AWS "full jitter" recipe: clients pick a
     * random delay in {@code [0, base * 2^attempt]} so retries naturally de-cluster.
     *
     * @param attempt 0-based retry counter; clamped to {@link #MAX_EXPONENT} to avoid overflow
     * @param baseMs  base delay before scaling
     * @param maxMs   hard upper bound on the returned delay
     * @return delay in milliseconds, never negative, never &gt; {@code maxMs}
     */
    static long exponentialBackoffMillis(int attempt, long baseMs, long maxMs) {
        int clampedExponent = Math.max(0, Math.min(attempt, MAX_EXPONENT));
        long exponentialDelay = baseMs * (1L << clampedExponent);
        long jitter = ThreadLocalRandom.current().nextLong(JITTER_MS + 1);
        return Math.min(exponentialDelay + jitter, maxMs);
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
