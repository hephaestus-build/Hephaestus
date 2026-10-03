package de.tum.cit.aet.hephaestus.core.runtime.hub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.FrameCodec;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class WorkerSessionRegistryTest extends BaseUnitTest {

    private final FrameCodec codec = new FrameCodec(new ObjectMapper());

    @Test
    void drainsBeforeWebServerShutdown() {
        WorkerSessionRegistry registry =
                new WorkerSessionRegistry(mock(ApplicationEventPublisher.class), new SimpleMeterRegistry());

        assertThat(registry.getPhase()).isGreaterThan(WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE);
    }

    @Test
    void duplicateRegistrationEvictsOlderAndKeepsNewer() throws Exception {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        WorkerSessionRegistry registry = new WorkerSessionRegistry(events, new SimpleMeterRegistry());

        WebSocketSession firstTransport = newTransport();
        WebSocketSession secondTransport = newTransport();
        WorkerSession first = sessionFor("worker-1", "session-1", firstTransport);
        registry.register(first);
        WorkerSession second = sessionFor("worker-1", "session-2", secondTransport);
        WorkerSession registered = registry.register(second);

        assertThat(registered).isSameAs(second);
        assertThat(registry.findByWorkerId("worker-1")).contains(second);
        verify(firstTransport).close(any());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, times(1)).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOfSatisfying(WorkerDisconnectedEvent.class, e -> {
            assertThat(e.reason()).isEqualTo("duplicate-evicted");
            assertThat(e.sessionId()).isEqualTo("session-1");
        });
    }

    @Test
    void unregisterIsIdentityGated() {
        // Guards against a stale handler removing a fresh session installed by a reconnect.
        WorkerSessionRegistry registry =
                new WorkerSessionRegistry(mock(ApplicationEventPublisher.class), new SimpleMeterRegistry());
        WorkerSession first = newSession("worker-1", "session-1");
        registry.register(first);
        WorkerSession second = newSession("worker-1", "session-2");
        registry.register(second); // evicts first
        registry.unregister(first, "ws-close:1000"); // should not remove second
        assertThat(registry.findByWorkerId("worker-1")).contains(second);
    }

    private WorkerSession sessionFor(String workerId, String sessionId, WebSocketSession transport) {
        return new WorkerSession(
                workerId, sessionId, "jti-" + sessionId, Instant.now().plusSeconds(3600), transport, codec);
    }

    private WebSocketSession newTransport() {
        WebSocketSession transport = mock(WebSocketSession.class);
        lenient().when(transport.isOpen()).thenReturn(true);
        lenient().when(transport.getAttributes()).thenReturn(new HashMap<>());
        return transport;
    }

    private WorkerSession newSession(String workerId, String sessionId) {
        return sessionFor(workerId, sessionId, newTransport());
    }
}
