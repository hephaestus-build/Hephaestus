package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Message;
import io.nats.client.impl.NatsJetStreamMetaData;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class IntegrationMessageMetersTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final IntegrationMessageMeters meters =
            new IntegrationMessageMeters(registry, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Message delivery(long deliveredCount, Duration waited) {
        var metadata = mock(NatsJetStreamMetaData.class);
        when(metadata.deliveredCount()).thenReturn(deliveredCount);
        if (deliveredCount == 1)
            when(metadata.timestamp()).thenReturn(NOW.minus(waited).atZone(ZoneOffset.UTC));
        var message = mock(Message.class);
        when(message.isJetStream()).thenReturn(true);
        when(message.metaData()).thenReturn(metadata);
        if (deliveredCount == 1) when(message.getSubject()).thenReturn("github.acme.repo.issues");
        return message;
    }

    @Test
    void shouldRecordHowLongAFirstDeliveryWaitedInItsStream() {
        meters.received(delivery(1, Duration.ofMinutes(7)));

        var lag = registry.get("integration.message.lag").tag("kind", "github").timer();
        assertThat(lag.count()).isEqualTo(1);
        assertThat(lag.totalTime(TimeUnit.SECONDS)).isEqualTo(420);
    }

    @Test
    void shouldLeaveARetryOutOfTheWait() {
        meters.received(delivery(2, Duration.ofMinutes(7)));

        assertThat(registry.find("integration.message.lag").timer()).isNull();
    }

    @Test
    void shouldNotStopTheHandlingOfAMessageWithUnreadableMetadata() {
        var message = mock(Message.class);
        when(message.isJetStream()).thenReturn(true);
        when(message.metaData()).thenThrow(new IllegalArgumentException("not a JetStream reply subject"));

        meters.received(message);

        assertThat(registry.find("integration.message.lag").timer()).isNull();
    }

    @Test
    void shouldNotStopAHandlingWhoseMessageCannotBeLabelled() {
        var message = mock(Message.class);
        when(message.getSubject()).thenThrow(new IllegalStateException("detached message"));

        assertThatCode(() -> meters.handled(message, IntegrationMessageMeters.Outcome.COMPLETED, 1))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldIgnoreAMessageThatTheStreamDidNotStore() {
        meters.received(mock(Message.class));

        assertThat(registry.find("integration.message.lag").timer()).isNull();
    }
}
