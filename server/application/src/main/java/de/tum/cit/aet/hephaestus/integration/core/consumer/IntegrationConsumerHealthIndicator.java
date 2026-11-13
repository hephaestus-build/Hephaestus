package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandlerRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Actuator health of the provider event consumer: {@code UP} while this server claims events, with when it last handled
 * and last failed one. It belongs to neither the liveness nor the readiness group; the backlog alerts read the inbox.
 */
@Component
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnServerRole
public class IntegrationConsumerHealthIndicator implements HealthIndicator {

    private final ProviderEventConsumer consumer;
    private final IntegrationMessageHandlerRegistry handlerRegistry;
    private final IntegrationMessageDispatcher dispatcher;

    public IntegrationConsumerHealthIndicator(
            ProviderEventConsumer consumer,
            IntegrationMessageHandlerRegistry handlerRegistry,
            IntegrationMessageDispatcher dispatcher) {
        this.consumer = consumer;
        this.handlerRegistry = handlerRegistry;
        this.dispatcher = dispatcher;
    }

    @Override
    public Health health() {
        ProviderEventConsumer.Status status = consumer.status();
        Health.Builder builder = status.running() ? Health.up() : Health.outOfService();
        builder.withDetail("claimers", status.claimers())
                .withDetail("handlerCount", handlerRegistry.handlerCount())
                .withDetail("parserCount", dispatcher.parserCount());
        if (status.lastHandledAt() != null)
            builder.withDetail("lastHandledAt", status.lastHandledAt().toString());
        if (status.lastFailedAt() != null)
            builder.withDetail("lastFailedAt", status.lastFailedAt().toString());
        return builder.build();
    }
}
