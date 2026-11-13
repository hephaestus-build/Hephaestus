package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.ProviderEvent;
import de.tum.cit.aet.hephaestus.integration.core.sync.activity.ConnectionActivityRecorder;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.sql.SQLTransientException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;

class ProviderEventConsumerLoggingTest extends BaseUnitTest {

    private static final String CANARY = "private-payload-canary@example.test";

    @ParameterizedTest
    @CsvSource({"1,55P03", "2,55P03", "1,private-payload-canary@example.test"})
    void shouldIdentifyAFailedEventAndItsApplicationCallSiteWithoutExposingDriverText(int attempt, String sqlState) {
        var sql = new SQLTransientException(CANARY, sqlState);
        var frames = new StackTraceElement[42];
        Arrays.fill(frames, new StackTraceElement("org.framework.Proxy", "invoke", "Proxy.java", 1));
        frames[40] =
                new StackTraceElement(ProviderEventStore.class.getName(), "fenceStep", "ProviderEventStore.java", 193);
        frames[41] = new StackTraceElement(DeliveryInbox.class.getName(), "claim", "DeliveryInbox.java", 42);
        sql.setStackTrace(frames);
        var failure = new DataAccessResourceFailureException(CANARY, sql);
        var store = mock(ProviderEventStore.class);
        var failures = mock(IntegrationPoisonHandler.class);
        var dispatcher = mock(IntegrationMessageDispatcher.class);
        var handler = mock(IntegrationMessageHandler.class);
        String subject = "github.private.repository.message";
        when(dispatcher.dispatch(subject)).thenReturn(Optional.of(handler));
        doThrow(failure).when(handler).onMessage(any(ProviderEvent.class));
        var claimed = new ProviderEventStore.Claimed(
                1234L, 41L, new ProviderEvent(subject, Map.of(), new byte[0], Instant.EPOCH), attempt, 0, 7);
        var consumer = new ProviderEventConsumer(
                store,
                dispatcher,
                List.of(),
                DeliveryInboxes.passthrough(),
                failures,
                EventTraceContexts.none(),
                EventTraceContexts.messageMeters(),
                mock(ConnectionActivityRecorder.class),
                new ProviderEventNotifications(),
                ProviderEventConsumerPropertiesFixture.defaults(),
                mock(ApplicationEventPublisher.class),
                Clock.systemUTC());
        Logger logger = (Logger) LoggerFactory.getLogger(ProviderEventConsumer.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            consumer.handle(claimed);
        } finally {
            consumer.close();
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).hasSize(1);
        ILoggingEvent record = appender.list.getFirst();
        assertThat(record.getLevel()).isEqualTo(Level.ERROR);
        assertThat(record.getFormattedMessage()).isEqualTo("Provider event handler failed");
        assertThat(record.getThrowableProxy()).isNull();
        var fields = record.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
        assertThat(fields)
                .containsEntry("event.name", "integration.message.failed")
                .containsEntry("integration.operation", "handle")
                .containsEntry("integration.event.id", claimed.id())
                .containsEntry("integration.scope.id", claimed.workspaceId());
        assertThat(fields.toString()).doesNotContain(CANARY, subject);
        if (sqlState.equals("55P03")) {
            assertThat(fields).containsEntry("db.response.status_code", "55P03");
        } else {
            assertThat(fields).doesNotContainKey("db.response.status_code");
        }
        if (attempt == 1) {
            assertThat(fields.get("error.stack_trace"))
                    .asInstanceOf(InstanceOfAssertFactories.LIST)
                    .hasSizeLessThanOrEqualTo(32)
                    .contains(frames[0].toString(), frames[40].toString(), frames[41].toString());
        } else {
            assertThat(fields).doesNotContainKey("error.stack_trace");
        }
        verify(failures).handleFailure(claimed, failure);
    }
}
