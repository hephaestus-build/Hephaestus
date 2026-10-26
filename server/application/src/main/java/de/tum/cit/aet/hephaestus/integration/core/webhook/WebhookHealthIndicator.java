package de.tum.cit.aet.hephaestus.integration.core.webhook;

import io.nats.client.Connection;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Reports {@code UP} while the NATS connection is CONNECTED, which is what this process needs to publish.
 *
 * <p>It reads the connection state the client already holds and makes no broker request. A stream request takes
 * as long as the slowest broker operation, so a health check that made one would be slow whenever the broker's
 * disk is. {@link WebhookStreamMonitor} reports stream reachability off the request path, and the bootstrap
 * refuses to start without the streams.
 */
public class WebhookHealthIndicator implements HealthIndicator {

    private final Connection connection;

    WebhookHealthIndicator(Connection connection) {
        this.connection = connection;
    }

    @Override
    public Health health() {
        Connection.Status status = connection.getStatus();
        Health.Builder health = status == Connection.Status.CONNECTED ? Health.up() : Health.down();
        return health.withDetail("natsStatus", status.name()).build();
    }
}
