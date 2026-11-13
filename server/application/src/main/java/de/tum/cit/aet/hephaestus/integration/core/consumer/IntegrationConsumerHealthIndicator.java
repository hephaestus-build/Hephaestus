package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandlerRegistry;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Actuator health of the provider event consumer: {@code UP} while this server's {@link ProviderEventClaimers} run,
 * with when it last handled and last failed an event. A process without background work has no claimers, so it reports
 * {@code OUT_OF_SERVICE}. It belongs to neither the liveness nor the readiness group; the backlog alerts read the inbox.
 */
@Component
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnServerRole
public class IntegrationConsumerHealthIndicator implements HealthIndicator {

    private final ProviderEventConsumer consumer;
    private final @Nullable ProviderEventClaimers claimers;
    private final IntegrationMessageHandlerRegistry handlerRegistry;
    private final IntegrationMessageDispatcher dispatcher;

    public IntegrationConsumerHealthIndicator(
            ProviderEventConsumer consumer,
            Optional<ProviderEventClaimers> claimers,
            IntegrationMessageHandlerRegistry handlerRegistry,
            IntegrationMessageDispatcher dispatcher) {
        this.consumer = consumer;
        this.claimers = claimers.orElse(null);
        this.handlerRegistry = handlerRegistry;
        this.dispatcher = dispatcher;
    }

    @Override
    public Health health() {
        ProviderEventClaimers running = claimers;
        Health.Builder builder = running != null && running.isRunning() ? Health.up() : Health.outOfService();
        builder.withDetail("claimers", running == null ? 0 : running.alive())
                .withDetail("handlerCount", handlerRegistry.handlerCount())
                .withDetail("parserCount", dispatcher.parserCount());
        Instant handled = consumer.lastHandledAt();
        if (handled != null) builder.withDetail("lastHandledAt", handled.toString());
        Instant failed = consumer.lastFailedAt();
        if (failed != null) builder.withDetail("lastFailedAt", failed.toString());
        return builder.build();
    }
}
