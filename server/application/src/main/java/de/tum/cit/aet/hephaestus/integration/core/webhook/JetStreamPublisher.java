package de.tum.cit.aet.hephaestus.integration.core.webhook;

import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import io.github.resilience4j.core.IntervalFunction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.PublishOptions;
import io.nats.client.impl.Headers;
import java.io.IOException;
import java.io.Serial;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes {@link PublishRequest}s to JetStream within one deadline, with {@code Nats-Msg-Id} for server-side dedup
 * and Micrometer counters. Tracks in-flight publishes through a {@link Phaser} so {@link WebhookGracefulShutdown} can
 * drain them before the NATS connection closes. Synchronous from the caller's perspective: controllers wait for the
 * ack so they can return 503 when the deadline passes.
 *
 * <p>A broker on a saturated disk acks late, not never, so an attempt waits for its ack until the deadline. If the
 * client stops waiting first (its request cleanup runs every 5 s), the next attempt carries the same
 * {@code Nats-Msg-Id}, so a copy that the broker stored late is acknowledged as a duplicate, not stored twice. A
 * failure that the broker answers at once (no responders while no stream listens, a dropped connection) is retried
 * with jittered exponential backoff until the deadline. A refusal by the stream is final. When the deadline ends the
 * publish, the last attempt's cause is the one reported: no responders followed by a stall in the tail reads
 * {@code timeout}.
 */
public class JetStreamPublisher {

    private static final Logger log = LoggerFactory.getLogger(JetStreamPublisher.class);

    private final JetStream jetStream;
    private final WebhookProperties properties;
    private final MeterRegistry meterRegistry;
    private final Counter retryCounter;
    private final Phaser inFlight = new Phaser(1);

    JetStreamPublisher(JetStream jetStream, WebhookProperties properties, MeterRegistry meterRegistry) {
        this.jetStream = jetStream;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        // Registered up front, so a rate over a stream that received nothing yet reads 0, not no data.
        for (String stream : WebhookJetStreamBootstrap.STREAMS) {
            published(meterRegistry, stream, SUCCESS);
            published(meterRegistry, stream, FAILURE);
        }
        this.retryCounter =
                Counter.builder(IntegrationCoreMetrics.WEBHOOK_PUBLISH_RETRY).register(meterRegistry);
    }

    /** An attempt needs this long to be worth sending: a copy whose ack nobody awaits only loads the broker. */
    static final Duration MIN_ATTEMPT = Duration.ofMillis(250);

    private static final String SUCCESS = "success";
    private static final String FAILURE = "failure";
    private static final String OTHER_STREAM = "other";

    private static Counter published(MeterRegistry meterRegistry, String stream, String outcome) {
        return Counter.builder(IntegrationCoreMetrics.WEBHOOK_PUBLISH)
                .description("Webhook deliveries that the broker stored, or did not store in time, by stream")
                .tag("stream", stream)
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    /** The stream as a bounded tag value: a subject outside the known streams counts as {@code other}. */
    static String streamTag(String subject) {
        int firstDot = subject.indexOf('.');
        String prefix = firstDot <= 0 ? "" : subject.substring(0, firstDot);
        for (String stream : WebhookJetStreamBootstrap.STREAMS) {
            if (stream.equals(prefix)) return stream;
        }
        return OTHER_STREAM;
    }

    private void count(PublishRequest request, String outcome) {
        published(meterRegistry, streamTag(request.subject()), outcome).increment();
    }

    /** Publishes within {@code hephaestus.webhook.publish.deadline}, inside the providers' 10 s response window. */
    public void publish(PublishRequest request) {
        publishWithin(request, properties.publish().deadline());
    }

    /** Publishes within {@code deadline}, for a provider that answers sooner (Slack waits 3 s). */
    public void publishFast(PublishRequest request, Duration deadline) {
        publishWithin(request, deadline);
    }

    private void publishWithin(PublishRequest request, Duration budget) {
        inFlight.register();
        long deadline = System.nanoTime() + budget.toNanos();
        IntervalFunction backoff =
                IntervalFunction.ofExponentialRandomBackoff(properties.publish().retryBaseDelay(), 2.0, 0.25);
        int attempts = 0;
        try {
            // Built once: every attempt carries the same headers and Nats-Msg-Id, so the broker dedups the copies.
            Headers headers = new Headers();
            for (Map.Entry<String, String> entry : request.headers().entrySet()) {
                headers.add(entry.getKey(), entry.getValue());
            }
            PublishOptions options;
            try {
                options = PublishOptions.builder()
                        .messageId(request.dedupId())
                        .expectedStream(streamFor(request.subject()))
                        .build();
            } catch (IllegalArgumentException e) {
                count(request, FAILURE);
                throw new PublishFailedException(
                        new Failure(Cause.REJECTED, "no stream for the subject", e), 0, request.subject());
            }
            while (true) {
                attempts++;
                Failure failure = attempt(request.subject(), headers, request.body(), options, deadline);
                if (failure == null) {
                    count(request, SUCCESS);
                    return;
                }
                long pause =
                        failure.cause() == Cause.TIMEOUT ? 0 : TimeUnit.MILLISECONDS.toNanos(backoff.apply(attempts));
                if (!failure.cause().retryable() || deadline - System.nanoTime() - pause < MIN_ATTEMPT.toNanos()) {
                    count(request, FAILURE);
                    throw new PublishFailedException(failure, attempts, request.subject());
                }
                TimeUnit.NANOSECONDS.sleep(pause);
                retryCounter.increment();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            count(request, FAILURE);
            throw new PublishFailedException(
                    new Failure(Cause.INTERRUPTED, "interrupted", e), attempts, request.subject());
        } finally {
            inFlight.arriveAndDeregister();
        }
    }

    /** One publish and the wait for its ack; {@code null} when the broker stored the message. */
    private @Nullable Failure attempt(
            String subject, Headers headers, byte[] body, PublishOptions options, long deadline)
            throws InterruptedException {
        try {
            jetStream
                    .publishAsync(subject, headers, body, options)
                    .get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            return null;
        } catch (TimeoutException | CancellationException e) {
            return new Failure(Cause.TIMEOUT, "no ack before the deadline", e);
        } catch (ExecutionException e) {
            return classify(e.getCause());
        } catch (IllegalStateException e) {
            // The client refuses a request on a closed or draining connection, which is final, and while its reconnect
            // buffer is full, which passes.
            String message = String.valueOf(e.getMessage());
            return new Failure(message.contains("reconnect") ? Cause.CONNECTION : Cause.CONNECTION_CLOSED, message, e);
        } catch (RuntimeException e) {
            // The client rejects the message itself, for example a payload over the broker's max_payload.
            return new Failure(Cause.REJECTED, e.getClass().getSimpleName(), e);
        }
    }

    static Failure classify(@Nullable Throwable thrown) {
        // The client's publish future rethrows IOException and JetStreamApiException wrapped in a plain
        // RuntimeException (or a CompletionException); the cause names the failure.
        Throwable cause = thrown;
        while ((cause instanceof CompletionException || (cause != null && cause.getClass() == RuntimeException.class))
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof TimeoutException || cause instanceof CancellationException) {
            return new Failure(Cause.TIMEOUT, "no ack before the client stopped waiting", cause);
        }
        if (cause instanceof JetStreamApiException api) {
            String description = String.valueOf(api.getErrorDescription());
            String lower = description.toLowerCase(Locale.ROOT);
            // A stream at its bound with discard-new, or a broker out of storage.
            Cause refusal =
                    lower.contains("maximum") || lower.contains("insufficient") ? Cause.STREAM_FULL : Cause.REJECTED;
            return new Failure(refusal, "code " + api.getApiErrorCode() + ": " + description, api);
        }
        if (cause instanceof IOException io) {
            // The client turns a status reply into "Error Publishing: <status>"; 503 is NATS' "no responders".
            String message = String.valueOf(io.getMessage());
            return new Failure(message.contains("503") ? Cause.NO_RESPONDERS : Cause.CONNECTION, message, io);
        }
        return new Failure(
                Cause.REJECTED, cause == null ? "unknown" : cause.getClass().getSimpleName(), cause);
    }

    /** Why a publish ended without an ack; {@code TIMEOUT} and the transient causes are retried within the deadline. */
    public enum Cause {
        TIMEOUT(true),
        NO_RESPONDERS(true),
        CONNECTION(true),
        CONNECTION_CLOSED(false),
        STREAM_FULL(false),
        REJECTED(false),
        INTERRUPTED(false);

        private final boolean retryable;

        Cause(boolean retryable) {
            this.retryable = retryable;
        }

        boolean retryable() {
            return retryable;
        }

        /** The cause as a log value: {@code no_responders}. */
        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A cause, the broker's or client's own words for it, and what was thrown. */
    public record Failure(
            Cause cause, String detail, @Nullable Throwable error) {

        public Failure(Cause cause, String detail) {
            this(cause, detail, null);
        }
    }

    /**
     * Derives the JetStream stream name from the subject prefix (first dot-segment).
     * Kept kind-agnostic so a new integration adapter only needs to (a) add a stream in
     * {@link WebhookJetStreamBootstrap#STREAMS} and (b) emit subjects under {@code <name>.…} —
     * no edit to this method.
     */
    private static String streamFor(String subject) {
        int firstDot = subject.indexOf('.');
        if (firstDot <= 0) {
            throw new IllegalArgumentException("Subject must be '<stream>.<...>': '" + subject + "'");
        }
        return subject.substring(0, firstDot);
    }

    void awaitInFlight(Duration timeout) {
        try {
            inFlight.awaitAdvanceInterruptibly(inFlight.arrive(), timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException e) {
            log.warn("In-flight webhook publishes did not drain within {}", timeout);
        }
    }

    public static class PublishFailedException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final Cause failureCause;
        private final int attempts;
        private final String detail;

        public PublishFailedException(Failure failure, int attempts, String subject) {
            super(
                    "Webhook not stored: cause=" + failure.cause().label() + ", attempts=" + attempts + ", subject="
                            + subject + ", detail=" + failure.detail(),
                    failure.error());
            this.failureCause = failure.cause();
            this.attempts = attempts;
            this.detail = failure.detail();
        }

        /** Why the broker did not store the delivery. */
        public Cause failureCause() {
            return failureCause;
        }

        public int attempts() {
            return attempts;
        }

        /** The broker's or client's own words for the cause; carries no delivery content. */
        public String detail() {
            return detail;
        }
    }
}
