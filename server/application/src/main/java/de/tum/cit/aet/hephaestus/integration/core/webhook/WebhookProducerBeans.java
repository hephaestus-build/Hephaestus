package de.tum.cit.aet.hephaestus.integration.core.webhook;

import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.integration.core.consumer.NatsConnectionProperties;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamOptions;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The NATS <em>producer</em> cluster contributed by {@link WebhookConfiguration} on the
 * webhook runtime role: JetStream + management handles, the publish-retry policy, the
 * {@link JetStreamPublisher}, the {@link WebhookJetStreamBootstrap}, and the {@link WebhookGracefulShutdown}.
 *
 * <p><strong>Not component-scanned.</strong> This is a plain class with {@code @Bean} factory
 * methods (no {@code @Configuration}/{@code @Component} stereotype), so it is only contributed
 * via {@code @Import} from the webhook host config.
 *
 * <p>{@link WebhookStreamMonitor} and {@link WebhookPayloadCapacityCheck} are contributed here
 * rather than beside the health indicator: both read the broker, and both report on ingestion
 * rather than on the receiver's own liveness.
 */
class WebhookProducerBeans {

    @Bean
    JetStream webhookJetStream(@Qualifier("natsConnection") Connection natsConnection) throws IOException {
        return natsConnection.jetStream();
    }

    @Bean
    @Primary
    JetStreamManagement webhookJetStreamManagement(@Qualifier("natsConnection") Connection natsConnection)
            throws IOException {
        return natsConnection.jetStreamManagement();
    }

    /**
     * The handle {@link WebhookJetStreamBootstrap} applies limit changes through. Separate from
     * {@link #webhookJetStreamManagement} because the two want opposite timeouts: the health
     * indicator and the monitor share that one and need it to fail fast, while bounding a stream
     * that has outgrown its new limit sheds the excess before the broker answers and takes as long
     * as the bytes require. Sharing one handle means either readiness waits on a delete or the
     * delete reports a failure the broker is still completing.
     */
    @Bean
    JetStreamManagement webhookAdminJetStreamManagement(
            @Qualifier("natsConnection") Connection natsConnection, WebhookProperties properties) throws IOException {
        return natsConnection.jetStreamManagement(JetStreamOptions.builder()
                .requestTimeout(properties.stream().limitUpdateTimeout())
                .build());
    }

    @Bean
    Retry webhookPublishRetry(WebhookProperties properties) {
        WebhookProperties.Publish p = properties.publish();
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(p.maxRetries())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(p.retryBaseDelay(), 2.0, 0.25))
                // Subject misconfiguration (unknown stream prefix) is permanent; don't burn retries on it.
                .ignoreExceptions(IllegalArgumentException.class)
                .failAfterMaxAttempts(true)
                .build();
        return Retry.of("webhook-publish", config);
    }

    @Bean
    JetStreamPublisher jetStreamPublisher(
            JetStream jetStream, Retry webhookPublishRetry, WebhookProperties properties, MeterRegistry meterRegistry) {
        return new JetStreamPublisher(jetStream, webhookPublishRetry, properties, meterRegistry);
    }

    @Bean
    WebhookJetStreamBootstrap webhookJetStreamBootstrap(
            @Qualifier("webhookAdminJetStreamManagement") JetStreamManagement jsm, WebhookProperties properties) {
        return new WebhookJetStreamBootstrap(jsm, properties);
    }

    @Bean
    WebhookStreamMonitor webhookStreamMonitor(
            JetStreamManagement jsm,
            WebhookProperties properties,
            NatsConnectionProperties natsProperties,
            MeterRegistry meterRegistry) {
        // The webhook and server containers must be given the same name, or the monitor counts none of
        // the server's durables: docker/compose.core.yaml forwards it to both for that reason.
        String durableBase = natsProperties.durableConsumerName();
        return new WebhookStreamMonitor(
                jsm,
                properties,
                durableBase == null || durableBase.isBlank() ? "hephaestus" : durableBase,
                meterRegistry);
    }

    @Bean
    WebhookPayloadCapacityCheck webhookPayloadCapacityCheck(
            @Qualifier("natsConnection") Connection natsConnection, WebhookProperties properties) {
        return new WebhookPayloadCapacityCheck(natsConnection, properties);
    }

    @Bean
    WebhookGracefulShutdown webhookGracefulShutdown(JetStreamPublisher publisher, WebhookProperties properties) {
        return new WebhookGracefulShutdown(publisher, properties);
    }
}
