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
import de.tum.cit.aet.hephaestus.observability.LogCapture;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.SocketException;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;

class ProviderEventConsumerLoggingTest extends BaseUnitTest {

    private static final String CANARY = "private-payload-canary@example.test";

    @ParameterizedTest
    @CsvSource({
        "1,53300,false",
        "2,53300,false",
        "1,private-payload-canary@example.test,false",
        "1,08006,true",
        "2,08001,true"
    })
    void shouldIdentifyAFailedEventAndItsApplicationCallSiteWithoutExposingDriverText(
            int attempt, String sqlState, boolean transportCause) {
        var sql = new SQLTransientException(CANARY, sqlState);
        var frames = new StackTraceElement[42];
        Arrays.fill(frames, new StackTraceElement("org.framework.Proxy", "invoke", "Proxy.java", 1));
        frames[40] =
                new StackTraceElement(ProviderEventStore.class.getName(), "fenceStep", "ProviderEventStore.java", 193);
        frames[41] = new StackTraceElement(DeliveryInbox.class.getName(), "claim", "DeliveryInbox.java", 42);
        sql.setStackTrace(frames);
        if (transportCause) {
            var socket = new SocketException(CANARY);
            socket.setStackTrace(frames);
            sql.initCause(socket);
        }
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
        assertThat(record.getLevel()).isEqualTo(Level.WARN);
        assertThat(record.getFormattedMessage()).isEqualTo("Provider event handler failed");
        assertThat(record.getThrowableProxy()).isNull();
        var fields = record.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
        assertThat(fields)
                .containsEntry("event.name", "integration.message.failed")
                .containsEntry("integration.operation", "handle")
                .containsEntry("integration.event.id", claimed.id())
                .containsEntry("integration.scope.id", claimed.workspaceId());
        assertThat(fields.toString()).doesNotContain(CANARY, subject);
        if (sqlState.matches("[A-Z0-9]{5}")) {
            assertThat(fields).containsEntry("db.response.status_code", sqlState);
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

    @Test
    void shouldIdentifyTheDeadLetterAndItsSqlStateWithoutDriverText() {
        var store = mock(ProviderEventStore.class);
        var claimed = new ProviderEventStore.Claimed(
                2345L,
                41L,
                new ProviderEvent("github.private.repository.message", Map.of(), new byte[0], Instant.EPOCH),
                100,
                99,
                7);
        when(store.dead(claimed.id(), claimed.leaseEpoch(), SQLException.class.getName()))
                .thenReturn(true);
        var registry = new SimpleMeterRegistry();
        try (var logs = new LogCapture(IntegrationPoisonHandler.class)) {
            var handler =
                    new IntegrationPoisonHandler(ProviderEventConsumerPropertiesFixture.defaults(), registry, store);
            handler.handleFailure(claimed, new SQLException(CANARY, "23505"));
            var records = logs.events("integration.message.captured");
            assertThat(records).hasSize(1);
            assertThat(records.getFirst())
                    .containsEntry("integration.event.id", "2345")
                    .containsEntry("integration.operation", "capture-for-replay")
                    .containsEntry("db.response.status_code", "23505");
            assertThat(records.toString())
                    .doesNotContain(CANARY, claimed.event().subject());
        }
    }

    @ParameterizedTest
    @CsvSource({"0,-1", "8,-1", "42,-1", "8,0", "42,2", "42,31", "42,32", "42,40", "80,40"})
    void shouldBoundFramesAndKeepTheFailureOriginAndApplicationCallSite(int size, int application) {
        var frames = new StackTraceElement[size];
        for (int index = 0; index < size; index++) {
            frames[index] = new StackTraceElement("org.framework.Proxy" + index, "invoke", "Proxy.java", index);
        }
        if (application >= 0) {
            frames[application] = new StackTraceElement(
                    ProviderEventStore.class.getName(), "fenceStep", "ProviderEventStore.java", 193);
        }
        var failure = new SQLException(CANARY);
        failure.setStackTrace(frames);
        var trace = IntegrationPoisonHandler.diagnosticFrames(failure);
        assertThat(trace).hasSizeLessThanOrEqualTo(32).noneMatch(frame -> frame.contains(CANARY));
        if (size == 0) {
            assertThat(trace).isEmpty();
            return;
        }
        assertThat(trace.getFirst()).isEqualTo(frames[0].toString());
        if (application >= 0) assertThat(trace).contains(frames[application].toString());
        if (application >= 32) {
            assertThat(trace)
                    .containsSubsequence(
                            frames[14].toString(),
                            "... " + (application - 15) + " framework frames omitted ...",
                            frames[application].toString());
        } else {
            assertThat(trace).noneMatch(frame -> frame.contains("framework frames omitted"));
        }
    }

    @Test
    void shouldUseTheFirstValidSqlStateInTheCauseChain() {
        var driver = new SQLException(CANARY, "08006", new SocketException(CANARY));
        var pool = new SQLException(CANARY, CANARY, driver);
        assertThat(IntegrationPoisonHandler.sqlState(pool)).isEqualTo("08006");
    }
}
