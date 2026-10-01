package de.tum.cit.aet.hephaestus.integration.core.webhook;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookPropertiesFixture;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.JetStream;
import io.nats.client.PublishOptions;
import io.nats.client.impl.Headers;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class JetStreamPublisherRetryTest extends BaseUnitTest {

    private final WebhookProperties properties = new WebhookProperties(
            null,
            null,
            new WebhookProperties.TokenRotation(7, 90),
            new WebhookProperties.Publish(Duration.ofSeconds(2), 3, Duration.ofMillis(10)),
            WebhookPropertiesFixture.stream(),
            new WebhookProperties.Shutdown(Duration.ofSeconds(15)),
            new WebhookProperties.Http(26_214_400L),
            new WebhookProperties.Routing(null, null));

    @Test
    void exhaustsRetriesThenThrowsPublishFailedException() throws Exception {
        JetStream jetStream = mock(JetStream.class);
        // Each retry attempt fails synchronously through the async future.
        CompletableFuture<io.nats.client.api.PublishAck> failed =
                CompletableFuture.failedFuture(new IOException("upstream NATS broker error"));
        when(jetStream.publishAsync(
                        any(String.class), any(Headers.class), any(byte[].class), any(PublishOptions.class)))
                .thenReturn(failed);

        Retry retry = Retry.of(
                "test-fast",
                RetryConfig.custom()
                        .maxAttempts(properties.publish().maxRetries())
                        .intervalFunction(IntervalFunction.of(Duration.ofMillis(1)))
                        .failAfterMaxAttempts(true)
                        .build());
        JetStreamPublisher publisher = new JetStreamPublisher(jetStream, retry, properties, new SimpleMeterRegistry());
        PublishRequest request = new PublishRequest(
                "gitlab.org.repo.push", "gitlab-x", Map.of("Nats-Msg-Id", "gitlab-x"), new byte[] {1});

        assertThatThrownBy(() -> publisher.publish(request))
                .isInstanceOf(JetStreamPublisher.PublishFailedException.class)
                .hasMessageContaining("gitlab.org.repo.push");

        verify(jetStream, times(properties.publish().maxRetries()))
                .publishAsync(any(String.class), any(Headers.class), any(byte[].class), any(PublishOptions.class));
    }
}
