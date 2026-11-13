package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.handler.ProviderEvent;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class IntegrationMessageMetersTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final IntegrationMessageMeters meters =
            new IntegrationMessageMeters(registry, Clock.fixed(NOW, ZoneOffset.UTC));

    private static ProviderEvent stored(Duration waited) {
        return new ProviderEvent("github.acme.repo.issues", Map.of(), new byte[0], NOW.minus(waited));
    }

    @Test
    void shouldRecordHowLongAFirstAttemptWaitedInTheInbox() {
        meters.received(stored(Duration.ofMinutes(7)), 1);

        var lag = registry.get(IntegrationCoreMetrics.INTEGRATION_MESSAGE_LAG)
                .tags("kind", "github")
                .timer();
        assertThat(lag.count()).isEqualTo(1);
        assertThat(lag.totalTime(TimeUnit.MINUTES)).isEqualTo(7);
    }

    @Test
    void shouldLeaveARetryOutOfTheWait() {
        meters.received(stored(Duration.ofMinutes(7)), 2);

        assertThat(registry.find(IntegrationCoreMetrics.INTEGRATION_MESSAGE_LAG).timer())
                .isNull();
    }

    @Test
    void shouldLabelAnUnknownSubjectWithoutFailing() {
        meters.handled(
                new ProviderEvent("unknown", Map.of(), new byte[0], NOW),
                IntegrationMessageMeters.Outcome.COMPLETED,
                1);

        assertThat(registry.get(IntegrationCoreMetrics.INTEGRATION_MESSAGE_PROCESS_DURATION)
                        .tags("kind", "unknown", "outcome", "completed")
                        .timer()
                        .count())
                .isEqualTo(1);
    }
}
