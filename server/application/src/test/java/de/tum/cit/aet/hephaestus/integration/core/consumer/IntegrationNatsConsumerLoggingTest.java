package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.sync.activity.ConnectionActivityRecorder;
import io.nats.client.Message;
import io.nats.client.impl.NatsJetStreamMetaData;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** A failing webhook is redelivered with backoff; each failure line must stay bounded and free of its subject. */
@Tag("unit")
class IntegrationNatsConsumerLoggingTest {

    private static final String SUBJECT = "github.acme.private-repo-canary.push";

    @Test
    void shouldLogOnlyTheRouteDeliveryAndFailureType() {
        IntegrationMessageDispatcher dispatcher = mock(IntegrationMessageDispatcher.class);
        IntegrationMessageHandler handler = mock(IntegrationMessageHandler.class);
        when(handler.key()).thenReturn(new EventTypeKey(IntegrationKind.GITHUB, "push"));
        when(dispatcher.dispatch(SUBJECT)).thenReturn(Optional.of(handler));
        Message first = delivery(1);
        Message second = delivery(2);
        RuntimeException failure =
                new IllegalStateException("Key (email)=(private-email-canary@example.test) already exists");
        doThrow(failure).when(handler).onMessage(first);
        doThrow(failure).when(handler).onMessage(second);
        IntegrationNatsConsumer consumer = new IntegrationNatsConsumer(
                new NatsConnectionProperties(
                        true,
                        "nats://localhost:4222",
                        "heph",
                        new NatsConnectionProperties.Consumer(Duration.ofSeconds(60))),
                NatsConsumerPropertiesFixture.withFastPoisonBackoff(),
                scopeId -> Optional.empty(),
                dispatcher,
                mock(IntegrationPoisonHandler.class),
                new IntegrationConsumerStats(),
                mock(ConnectionActivityRecorder.class),
                List.of());

        Logger logger = (Logger) LoggerFactory.getLogger(IntegrationNatsConsumer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            consumer.handleMessage(7L, first);
            consumer.handleMessage(7L, second);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        List<ILoggingEvent> failures = appender.list.stream()
                .filter(event -> "Handler failed".equals(event.getFormattedMessage()))
                .toList();
        assertThat(failures).hasSize(2);
        for (ILoggingEvent event : failures) {
            assertThat(fields(event))
                    .containsEntry("event.name", "integration.message.failed")
                    .containsEntry("integration.route", "github.push")
                    .containsEntry("integration.scope.id", 7L)
                    .containsEntry("error.type", IllegalStateException.class.getName());
            assertThat(event.getFormattedMessage() + fields(event)).doesNotContain("canary");
        }
        assertThat(fields(failures.get(0))).containsEntry("integration.delivery", 1L);
        assertThat(failures.get(0).getThrowableProxy()).isNull();
        assertThat(fields(failures.get(1))).containsEntry("integration.delivery", 2L);
        assertThat(failures.get(1).getThrowableProxy()).isNull();
    }

    private static Message delivery(long count) {
        NatsJetStreamMetaData metadata = mock(NatsJetStreamMetaData.class);
        when(metadata.deliveredCount()).thenReturn(count);
        Message message = mock(Message.class);
        when(message.getSubject()).thenReturn(SUBJECT);
        when(message.metaData()).thenReturn(metadata);
        return message;
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        return event.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }
}
