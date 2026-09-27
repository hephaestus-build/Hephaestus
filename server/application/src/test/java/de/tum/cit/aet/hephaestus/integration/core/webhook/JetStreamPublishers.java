package de.tum.cit.aet.hephaestus.integration.core.webhook;

import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import io.github.resilience4j.retry.Retry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Connection;
import java.io.IOException;

/** The receiver's real publisher over a test broker connection, for suites that run without the webhook role. */
public final class JetStreamPublishers {

    private JetStreamPublishers() {}

    public static JetStreamPublisher of(Connection connection, WebhookProperties properties) throws IOException {
        return new JetStreamPublisher(
                connection.jetStream(),
                Retry.ofDefaults("test-webhook-publish"),
                properties,
                new SimpleMeterRegistry());
    }
}
