package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.ProviderEvent;
import de.tum.cit.aet.hephaestus.integration.core.sync.activity.ConnectionActivityRecorder;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.SocketException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * A failed delivery is a retry and logs at WARN with its delivery count. Only the verdict logs at ERROR, once: the dead
 * letter when the attempts run out. A transient outage retries without a limit and so never reaches ERROR.
 */
class ProviderEventRetrySeverityTest extends BaseUnitTest {

    private static final String SUBJECT = "github.acme.api.push";

    private final ProviderEventStore store = mock(ProviderEventStore.class);
    private final IntegrationMessageHandler handler = mock(IntegrationMessageHandler.class);
    private final ProviderEventConsumerProperties properties = ProviderEventConsumerPropertiesFixture.defaults();

    @Test
    void shouldLogOneErrorForARetrySequenceThatRunsOutOfAttempts() {
        doThrow(new IllegalStateException("handler defect")).when(handler).onMessage(any(ProviderEvent.class));

        List<ILoggingEvent> records = deliver(properties.poison().maxAttempts());

        assertThat(records)
                .filteredOn(record -> record.getLevel() == Level.ERROR)
                .singleElement()
                .satisfies(record -> assertThat(field(record, "event.name")).isEqualTo("integration.message.captured"));
        assertThat(records)
                .filteredOn(record -> "integration.message.failed".equals(field(record, "event.name")))
                .allSatisfy(record -> assertThat(record.getLevel()).isEqualTo(Level.WARN))
                .extracting(record -> field(record, "integration.delivery"))
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    }

    @Test
    void shouldLogNoErrorWhileATransientOutageRetries() {
        doThrow(new IllegalStateException("send failed", new SocketException("connection reset")))
                .when(handler)
                .onMessage(any(ProviderEvent.class));

        List<ILoggingEvent> records = deliver(3 * properties.poison().maxAttempts());

        assertThat(records).noneMatch(record -> record.getLevel() == Level.ERROR);
        assertThat(records)
                .filteredOn(record -> record.getLevel() == Level.WARN)
                .hasSize(30);
    }

    /** Delivers one event {@code attempts} times; the store answers as a database that records every outcome. */
    private List<ILoggingEvent> deliver(int attempts) {
        when(store.dead(anyLong(), anyInt(), anyString())).thenReturn(true);
        when(store.retry(anyLong(), anyInt(), any(), anyBoolean())).thenReturn(true);
        var dispatcher = mock(IntegrationMessageDispatcher.class);
        when(dispatcher.dispatch(SUBJECT)).thenReturn(Optional.of(handler));
        var consumer = new ProviderEventConsumer(
                store,
                dispatcher,
                List.of(),
                DeliveryInboxes.passthrough(),
                new IntegrationPoisonHandler(properties, new SimpleMeterRegistry(), store),
                EventTraceContexts.none(),
                EventTraceContexts.messageMeters(),
                mock(ConnectionActivityRecorder.class),
                new ProviderEventNotifications(),
                properties,
                mock(ApplicationEventPublisher.class),
                Clock.systemUTC());
        Logger logger = (Logger) LoggerFactory.getLogger(ProviderEventConsumer.class.getPackageName());
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var event = new ProviderEvent(SUBJECT, Map.of(), new byte[0], Instant.EPOCH);
            for (int attempt = 1; attempt <= attempts; attempt++) {
                consumer.handle(new ProviderEventStore.Claimed(1234L, 41L, event, attempt, attempt - 1, 7));
            }
        } finally {
            consumer.close();
            logger.detachAppender(appender);
            appender.stop();
        }
        return List.copyOf(appender.list);
    }

    private static @Nullable Object field(ILoggingEvent record, String key) {
        return record.getKeyValuePairs() == null
                ? null
                : record.getKeyValuePairs().stream()
                        .filter(pair -> pair.key.equals(key))
                        .map(pair -> pair.value)
                        .findFirst()
                        .orElse(null);
    }
}
