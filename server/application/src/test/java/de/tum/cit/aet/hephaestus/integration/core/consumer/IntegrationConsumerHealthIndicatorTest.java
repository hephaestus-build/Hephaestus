package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandlerRegistry;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class IntegrationConsumerHealthIndicatorTest extends BaseUnitTest {

    private static Health health(ProviderEventConsumer.Status status) {
        ProviderEventConsumer consumer = mock(ProviderEventConsumer.class);
        when(consumer.status()).thenReturn(status);
        IntegrationMessageHandlerRegistry registry = mock(IntegrationMessageHandlerRegistry.class);
        when(registry.handlerCount()).thenReturn(32);
        IntegrationMessageDispatcher dispatcher = mock(IntegrationMessageDispatcher.class);
        when(dispatcher.parserCount()).thenReturn(4);
        return new IntegrationConsumerHealthIndicator(consumer, registry, dispatcher).health();
    }

    @Test
    void shouldReportUpWithTheLastHandlingWhenTheConsumerRuns() {
        Instant handled = Instant.parse("2026-01-01T00:00:00Z");

        Health health = health(new ProviderEventConsumer.Status(true, 8, handled, null));

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("claimers", 8)
                .containsEntry("handlerCount", 32)
                .containsEntry("parserCount", 4)
                .containsEntry("lastHandledAt", handled.toString())
                .doesNotContainKey("lastFailedAt");
    }

    @Test
    void shouldReportOutOfServiceWhenTheConsumerDoesNotRun() {
        Health health = health(new ProviderEventConsumer.Status(false, 0, null, null));

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    }
}
