package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandlerRegistry;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class IntegrationConsumerHealthIndicatorTest extends BaseUnitTest {

    private final ProviderEventConsumer consumer = mock(ProviderEventConsumer.class);

    private Health health(Optional<ProviderEventClaimers> claimers) {
        IntegrationMessageHandlerRegistry registry = mock(IntegrationMessageHandlerRegistry.class);
        when(registry.handlerCount()).thenReturn(32);
        IntegrationMessageDispatcher dispatcher = mock(IntegrationMessageDispatcher.class);
        when(dispatcher.parserCount()).thenReturn(4);
        return new IntegrationConsumerHealthIndicator(consumer, claimers, registry, dispatcher).health();
    }

    private static ProviderEventClaimers claimers(boolean running, int alive) {
        ProviderEventClaimers claimers = mock(ProviderEventClaimers.class);
        when(claimers.isRunning()).thenReturn(running);
        when(claimers.alive()).thenReturn(alive);
        return claimers;
    }

    @Test
    void shouldReportUpWithTheLastHandlingWhenTheClaimersRun() {
        Instant handled = Instant.parse("2026-01-01T00:00:00Z");
        when(consumer.lastHandledAt()).thenReturn(handled);

        Health health = health(Optional.of(claimers(true, 8)));

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("claimers", 8)
                .containsEntry("handlerCount", 32)
                .containsEntry("parserCount", 4)
                .containsEntry("lastHandledAt", handled.toString())
                .doesNotContainKey("lastFailedAt");
    }

    @Test
    void shouldReportOutOfServiceWhenTheClaimersHaveStopped() {
        Health health = health(Optional.of(claimers(false, 0)));

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    }

    @Test
    void shouldReportOutOfServiceWithNoClaimerInAProcessWithoutBackgroundWork() {
        Health health = health(Optional.empty());

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(health.getDetails()).containsEntry("claimers", 0);
    }
}
