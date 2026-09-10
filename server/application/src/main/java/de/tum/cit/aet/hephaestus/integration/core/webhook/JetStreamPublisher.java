package de.tum.cit.aet.hephaestus.integration.core.webhook;

import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import io.github.resilience4j.retry.Retry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.PublishOptions;
import io.nats.client.api.PublishAck;
import io.nats.client.impl.Headers;
import java.io.IOException;
import java.io.Serial;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes {@link PublishRequest}s to JetStream with Resilience4j retry, {@code Nats-Msg-Id}
 * for server-side dedup, and Micrometer counters. Tracks in-flight publishes through a
 * {@link Phaser} so {@link WebhookGracefulShutdown} can drain them before the NATS connection
 * closes. Synchronous from the caller's perspective: controllers wait for the ack so they can
 * return 503 and let the provider retry on terminal failure.
 */
public class JetStreamPublisher {

    private static final Logger log = LoggerFactory.getLogger(JetStreamPublisher.class);

    private final JetStream jetStream;
    private final Retry retry;
    private final WebhookProperties properties;
    private final Counter successCounter;
    private final Counter failureCounter;
    private final Counter retryCounter;
    private final Phaser inFlight = new Phaser(1);

    JetStreamPublisher(JetStream jetStream, Retry retry, WebhookProperties properties, MeterRegistry meterRegistry) {
        this.jetStream = jetStream;
        this.retry = retry;
        this.properties = properties;
        this.successCounter = Counter.builder(IntegrationCoreMetrics.WEBHOOK_PUBLISH)
                .tag("outcome", "success")
                .register(meterRegistry);
        this.failureCounter = Counter.builder(IntegrationCoreMetrics.WEBHOOK_PUBLISH)
                .tag("outcome", "failure")
                .register(meterRegistry);
        this.retryCounter =
                Counter.builder(IntegrationCoreMetrics.WEBHOOK_PUBLISH_RETRY).register(meterRegistry);
        retry.getEventPublisher().onRetry(event -> retryCounter.increment());
    }

    public void publish(PublishRequest request) {
        inFlight.register();
        try {
            Retry.decorateCallable(
                            retry,
                            () -> publishOnce(request, properties.publish().timeout()))
                    .call();
            successCounter.increment();
        } catch (Exception e) {
            failureCounter.increment();
            throw new PublishFailedException(
                    "Failed to publish webhook to NATS after retries: subject=" + request.subject(), e);
        } finally {
            inFlight.arriveAndDeregister();
        }
    }

    public void publishFast(PublishRequest request, Duration timeout) {
        inFlight.register();
        try {
            publishOnce(request, timeout);
            successCounter.increment();
        } catch (Exception e) {
            failureCounter.increment();
            throw new PublishFailedException("Failed to publish webhook to NATS: subject=" + request.subject(), e);
        } finally {
            inFlight.arriveAndDeregister();
        }
    }

    private PublishAck publishOnce(PublishRequest request, Duration timeout)
            throws IOException, JetStreamApiException, InterruptedException, ExecutionException, TimeoutException {
        Headers headers = new Headers();
        for (Map.Entry<String, String> entry : request.headers().entrySet()) {
            headers.add(entry.getKey(), entry.getValue());
        }
        PublishOptions options = PublishOptions.builder()
                .messageId(request.dedupId())
                .expectedStream(streamFor(request.subject()))
                .build();
        return jetStream
                .publishAsync(request.subject(), headers, request.body(), options)
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
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

        public PublishFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
