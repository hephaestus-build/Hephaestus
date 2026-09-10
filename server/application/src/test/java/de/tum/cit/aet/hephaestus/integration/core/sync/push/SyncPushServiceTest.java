package de.tum.cit.aet.hephaestus.integration.core.sync.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.events.ConnectionLifecycleEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncStateChangedEvent;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.MessageHandler;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

class SyncPushServiceTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 42L;
    private static final long CONNECTION_ID = 7L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Mock
    private SyncEventHub hub;

    @Mock
    private Connection connection;

    @Mock
    private Dispatcher dispatcher;

    private SyncStateChangedEvent someEvent() {
        return new SyncStateChangedEvent(
                WORKSPACE_ID, CONNECTION_ID, IntegrationKind.GITHUB, SyncStateChangedEvent.Scope.JOB);
    }

    @Test
    void withoutNats_onSyncStateChanged_deliversDirectlyInProcess() {
        ObjectProvider<Connection> provider = objectProviderReturning(null);
        SyncPushService service = new SyncPushService(hub, MAPPER, provider, meters);
        service.subscribeIfNatsAvailable();

        service.onSyncStateChanged(someEvent());

        ArgumentCaptor<SyncEventHint> captor = ArgumentCaptor.forClass(SyncEventHint.class);
        verify(hub, times(1)).publish(eq(WORKSPACE_ID), captor.capture());
        assertThat(captor.getValue().scope()).isEqualTo("job");
        assertThat(captor.getValue().connectionId()).isEqualTo(CONNECTION_ID);
        verify(connection, never()).publish(anyString(), any(byte[].class));
        assertThat(pushCounter("local", "success")).isEqualTo(1.0);
    }

    @Test
    void connectionLifecycle_deliversConnectionInvalidation() {
        SyncPushService service = new SyncPushService(hub, MAPPER, objectProviderReturning(null), meters);
        service.subscribeIfNatsAvailable();

        service.onConnectionActivated(
                new ConnectionLifecycleEvent.Activated(CONNECTION_ID, WORKSPACE_ID, IntegrationKind.GITHUB));

        verify(hub).publish(WORKSPACE_ID, new SyncEventHint("connection", CONNECTION_ID));
    }

    @Test
    void shouldSubscribeOnlyAfterConstructionWhenNatsIsAvailable() {
        when(connection.createDispatcher(any(MessageHandler.class))).thenReturn(dispatcher);
        ObjectProvider<Connection> provider = objectProviderReturning(connection);

        SyncPushService service = new SyncPushService(hub, MAPPER, provider, meters);
        org.mockito.Mockito.verifyNoInteractions(connection);
        service.subscribeIfNatsAvailable();

        verify(dispatcher).subscribe("hephaestus.syncstatus.>");
    }

    @Test
    void withNats_onSyncStateChanged_publishesToNatsAndDoesNotDeliverLocally() {
        when(connection.createDispatcher(any(MessageHandler.class))).thenReturn(dispatcher);
        ObjectProvider<Connection> provider = objectProviderReturning(connection);
        SyncPushService service = new SyncPushService(hub, MAPPER, provider, meters);
        service.subscribeIfNatsAvailable();

        service.onSyncStateChanged(someEvent());

        ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(connection, times(1)).publish(eq("hephaestus.syncstatus.42"), payloadCaptor.capture());
        SyncEventHint published = MAPPER.readValue(payloadCaptor.getValue(), SyncEventHint.class);
        assertThat(published.scope()).isEqualTo("job");
        assertThat(published.connectionId()).isEqualTo(CONNECTION_ID);

        verify(hub, never()).publish(any(Long.class), any(SyncEventHint.class));
        assertThat(pushCounter("nats_publish", "success")).isEqualTo(1.0);
    }

    @Test
    void withNats_publishFailure_deliversLocallyAndRecordsBothOutcomes() {
        when(connection.createDispatcher(any(MessageHandler.class))).thenReturn(dispatcher);
        org.mockito.Mockito.doThrow(new IllegalStateException("broker unavailable"))
                .when(connection)
                .publish(anyString(), any(byte[].class));
        SyncPushService service = new SyncPushService(hub, MAPPER, objectProviderReturning(connection), meters);
        service.subscribeIfNatsAvailable();

        service.onSyncStateChanged(someEvent());

        verify(hub).publish(WORKSPACE_ID, new SyncEventHint("job", CONNECTION_ID));
        assertThat(pushCounter("nats_publish", "failure")).isEqualTo(1.0);
        assertThat(pushCounter("local", "success")).isEqualTo(1.0);
    }

    @Test
    void onMessage_deliversToHubExactlyOnce() {
        when(connection.createDispatcher(any(MessageHandler.class))).thenReturn(dispatcher);
        ObjectProvider<Connection> provider = objectProviderReturning(connection);
        SyncPushService service = new SyncPushService(hub, MAPPER, provider, meters);
        service.subscribeIfNatsAvailable();

        SyncEventHint hint = new SyncEventHint("resources", CONNECTION_ID);
        byte[] payload = MAPPER.writeValueAsBytes(hint);
        Message message = mock(Message.class);
        when(message.getSubject()).thenReturn("hephaestus.syncstatus.42");
        when(message.getData()).thenReturn(payload);

        service.onMessage(message);

        verify(hub, times(1)).publish(eq(WORKSPACE_ID), eq(hint));
        assertThat(pushCounter("nats_receive", "success")).isEqualTo(1.0);
    }

    @Test
    void onMessage_malformedSubject_isLoggedNotThrown() {
        ObjectProvider<Connection> provider = objectProviderReturning(null);
        SyncPushService service = new SyncPushService(hub, MAPPER, provider, meters);
        service.subscribeIfNatsAvailable();

        Message message = mock(Message.class);
        when(message.getSubject()).thenReturn("not.the.right.prefix");

        service.onMessage(message); // must not throw — malformed subject is caught + logged

        verify(hub, never()).publish(any(Long.class), any(SyncEventHint.class));
        assertThat(pushCounter("nats_receive", "failure")).isEqualTo(1.0);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<Connection> objectProviderReturning(@Nullable Connection connection) {
        ObjectProvider<Connection> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(connection);
        return provider;
    }

    private double pushCounter(String transport, String outcome) {
        return meters.get("integration.sync.push.messages")
                .tag("transport", transport)
                .tag("outcome", outcome)
                .counter()
                .count();
    }
}
