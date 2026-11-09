package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Whether the connected NATS broker is new enough for every feature: {@code DOWN} when it is older than
 * {@value IntegrationNatsConsumer#MINIMUM_BROKER_VERSION}, which every webhook replay needs. It belongs to neither the
 * liveness nor the readiness group: live delivery works on an older broker, so the Pod keeps serving and only the
 * overall health reports the gap. {@code UNKNOWN} until a connection reports the version.
 */
@Component
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnServerRole
public class NatsBrokerHealthIndicator implements HealthIndicator {

    private final IntegrationConsumerStats stats;

    public NatsBrokerHealthIndicator(IntegrationConsumerStats stats) {
        this.stats = stats;
    }

    @Override
    public Health health() {
        return stats.brokerVersion()
                .map(broker -> (broker.supported() ? Health.up() : Health.down())
                        .withDetail("version", broker.version())
                        .withDetail("minimumVersion", IntegrationNatsConsumer.MINIMUM_BROKER_VERSION)
                        .withDetail("documentation", IntegrationNatsConsumer.BROKER_REQUIREMENT_DOCS)
                        .build())
                .orElseGet(() -> Health.unknown().build());
    }
}
