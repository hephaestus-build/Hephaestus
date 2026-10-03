package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandlerRegistry;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Actuator health probe for the server-role NATS consumer. {@code UP} means the consumer is connected (or
 * intentionally disabled by config); {@code OUT_OF_SERVICE} means this runtime has not initialised a consumer;
 * {@code DOWN} means an enabled consumer is not connected.
 */
@Component
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnServerRole
public class IntegrationConsumerHealthIndicator implements HealthIndicator {

    private static final String STATUS_CONNECTED = "CONNECTED";
    private static final String STATUS_DISABLED = "DISABLED";

    private final IntegrationConsumerStats stats;
    private final IntegrationMessageHandlerRegistry handlerRegistry;
    private final IntegrationMessageDispatcher dispatcher;

    public IntegrationConsumerHealthIndicator(
            IntegrationConsumerStats stats,
            IntegrationMessageHandlerRegistry handlerRegistry,
            IntegrationMessageDispatcher dispatcher) {
        this.stats = stats;
        this.handlerRegistry = handlerRegistry;
        this.dispatcher = dispatcher;
    }

    @Override
    public Health health() {
        if (stats.natsConnectionStatus().isEmpty()) {
            return Health.outOfService()
                    .withDetail("reason", "no integration consumer initialised on this runtime role")
                    .withDetail("handlerCount", handlerRegistry.handlerCount())
                    .withDetail("parserCount", dispatcher.parserCount())
                    .build();
        }

        String connectionStatus = stats.natsConnectionStatus().orElseThrow();
        boolean disabled = STATUS_DISABLED.equalsIgnoreCase(connectionStatus);
        boolean connected = STATUS_CONNECTED.equalsIgnoreCase(connectionStatus);
        boolean healthy = disabled || connected;
        Health.Builder builder = healthy ? Health.up() : Health.down();

        builder.withDetail("natsConnectionStatus", connectionStatus)
                .withDetail("activeScopeConsumers", stats.activeScopeConsumerCount())
                .withDetail("installationConsumerActive", stats.installationConsumerActive())
                .withDetail("handlerCount", handlerRegistry.handlerCount())
                .withDetail("parserCount", dispatcher.parserCount());

        stats.lastDispatchAt().map(Instant::toString).ifPresent(ts -> builder.withDetail("lastDispatchAt", ts));
        stats.lastNakAt().map(Instant::toString).ifPresent(ts -> builder.withDetail("lastNakAt", ts));

        return builder.build();
    }
}
