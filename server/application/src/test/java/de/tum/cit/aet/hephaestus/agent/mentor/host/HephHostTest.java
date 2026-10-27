package de.tum.cit.aet.hephaestus.agent.mentor.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.HostSandbox;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.quality.Strictness;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** The worker's one Heph host: its start, the command channel only it may open, once, and its loss. */
class HephHostTest extends BaseUnitTest {

    private static final String TOKEN = "host-token";
    private static final Duration READY = Duration.ofSeconds(20);

    /** Starts hosts that run until the test says otherwise. */
    private static final class FakeSandbox implements HostSandbox {
        final List<Host> launched = new CopyOnWriteArrayList<>();
        final List<Host> removed = new CopyOnWriteArrayList<>();
        volatile boolean running = true;

        /** What the host's process does while the launch has not returned yet. */
        volatile Consumer<UUID> whileLaunching = id -> {};

        /** The launch fails after the host's process ran. */
        volatile boolean launchFails;

        @Override
        public Host launch(UUID id, HostSpec spec) throws IOException {
            whileLaunching.accept(id);
            if (launchFails) throw new IOException("the backend lost the host");
            Host host = new Host(id);
            launched.add(host);
            return host;
        }

        @Override
        public boolean isRunning(Host host) {
            return running;
        }

        @Override
        public void remove(Host host) {
            removed.add(host);
        }
    }

    @TempDir
    private Path workspace;

    private final FakeSandbox sandbox = new FakeSandbox();
    private final AtomicInteger lost = new AtomicInteger();
    private HephHost host;

    @BeforeEach
    void createHost() {
        host = new HephHost(sandbox);
        host.onLost(lost::incrementAndGet);
    }

    private static WebSocketSession socket(String id) {
        WebSocketSession socket = mock(WebSocketSession.class, withSettings().strictness(Strictness.LENIENT));
        when(socket.getId()).thenReturn(id);
        when(socket.isOpen()).thenReturn(true);
        when(socket.getAttributes()).thenReturn(new HashMap<>());
        return socket;
    }

    /** Starts a host and waits until it is launched; the future completes when its channel opens. */
    private CompletableFuture<Boolean> starting(Duration timeout) {
        CompletableFuture<Boolean> ready = CompletableFuture.supplyAsync(() -> host.awaitReady(timeout));
        await().atMost(READY).until(() -> !sandbox.launched.isEmpty());
        return ready;
    }

    private UUID hostId() {
        return sandbox.launched.getLast().id();
    }

    @Test
    void shouldBeReadyAndTakeCommandsWhenTheStartedHostOpensItsChannel() throws Exception {
        CompletableFuture<Boolean> ready = starting(READY);
        WebSocketSession channel = socket("s1");

        assertThat(host.connected(hostId(), channel)).isTrue();

        assertThat(ready.get(READY.toSeconds(), TimeUnit.SECONDS)).isTrue();
        assertThat(host.isReady()).isTrue();
        assertThat(host.awaitReady(READY)).isTrue();
        assertThat(sandbox.launched).hasSize(1);
        host.send("{\"type\":\"stop\"}");
        verify(channel).sendMessage(new TextMessage("{\"type\":\"stop\"}"));
    }

    /** The process connects as soon as it runs, and the launch returns only once the backend has seen it run. */
    @Test
    void shouldAcceptTheChannelOfAHostThatConnectsBeforeItsLaunchReturns() {
        WebSocketSession channel = socket("early");
        AtomicBoolean accepted = new AtomicBoolean();
        sandbox.whileLaunching = id -> accepted.set(host.connected(id, channel));

        assertThat(host.awaitReady(READY)).isTrue();

        assertThat(accepted).isTrue();
        assertThat(host.isReady()).isTrue();
    }

    @Test
    void shouldForgetTheEarlyChannelOfAHostWhoseLaunchFailed() throws Exception {
        WebSocketSession channel = socket("early");
        sandbox.whileLaunching = id -> host.connected(id, channel);
        sandbox.launchFails = true;

        assertThat(host.awaitReady(READY)).isFalse();

        assertThat(host.isReady()).isFalse();
        verify(channel).close();
    }

    @Test
    void shouldRefuseASecondChannelAndAChannelOfAnotherHostWhenTheHostIsConnected() throws Exception {
        CompletableFuture<Boolean> ready = starting(READY);
        UUID current = hostId();
        assertThat(host.connected(current, socket("s1"))).isTrue();
        ready.get(READY.toSeconds(), TimeUnit.SECONDS);

        assertThat(host.connected(current, socket("s2"))).isFalse();
        assertThat(host.connected(UUID.randomUUID(), socket("s3"))).isFalse();
        assertThat(host.isCurrent(UUID.randomUUID())).isFalse();
        assertThat(host.isCurrent(current)).isTrue();
    }

    @Test
    void shouldReportTheHostLostAndRemoveItWhenItsChannelCloses() throws Exception {
        CompletableFuture<Boolean> ready = starting(READY);
        UUID gone = hostId();
        WebSocketSession channel = socket("s1");
        host.connected(gone, channel);
        ready.get(READY.toSeconds(), TimeUnit.SECONDS);

        host.disconnected(socket("another"));
        assertThat(lost).hasValue(0);
        host.disconnected(channel);

        assertThat(lost).hasValue(1);
        assertThat(host.isReady()).isFalse();
        assertThat(host.isCurrent(gone)).isFalse();
        await().atMost(READY).until(() -> sandbox.removed.stream().anyMatch(h -> h.id().equals(gone)));
    }

    @Test
    void shouldKeepTheChannelWhileTheHostAnswersPingsAndCloseItWhenTheHostStopsAnswering() throws Exception {
        CompletableFuture<Boolean> ready = starting(READY);
        UUID stuck = hostId();
        WebSocketSession channel = socket("s1");
        host.connected(stuck, channel);
        ready.get(READY.toSeconds(), TimeUnit.SECONDS);

        for (int ping = 0; ping < 2 * HephHost.UNANSWERED_PINGS; ping++) {
            host.ping();
            host.ponged(channel);
        }
        assertThat(host.isReady()).isTrue();
        verify(channel, times(2 * HephHost.UNANSWERED_PINGS)).sendMessage(any(PingMessage.class));

        for (int ping = 0; ping < HephHost.UNANSWERED_PINGS; ping++) {
            host.ping();
        }
        assertThat(host.isReady()).isTrue();
        host.ponged(socket("another"));
        host.ping();

        verify(channel).close(CloseStatus.SESSION_NOT_RELIABLE);
        assertThat(lost).hasValue(1);
        assertThat(host.isReady()).isFalse();
        await().atMost(READY).until(() -> sandbox.removed.stream().anyMatch(h -> h.id().equals(stuck)));
    }

    @Test
    void shouldRemoveTheHostAndFailWhenItDoesNotConnectInTime() {
        assertThat(host.awaitReady(Duration.ofMillis(1))).isFalse();

        assertThat(sandbox.removed).containsExactlyElementsOf(sandbox.launched);
        assertThat(host.isCurrent(sandbox.launched.getFirst().id())).isFalse();
    }

    @Test
    void shouldFailWithoutWaitingOutTheTimeoutWhenTheHostExitsBeforeItConnects() throws Exception {
        sandbox.running = false;

        assertThat(CompletableFuture.supplyAsync(() -> host.awaitReady(Duration.ofHours(1)))
                        .get(READY.toSeconds(), TimeUnit.SECONDS))
                .isFalse();
        assertThat(sandbox.removed).containsExactlyElementsOf(sandbox.launched);
    }

    @Test
    void shouldAcceptOnlyTheCurrentHostWithItsSessionTokenWhenItOpensTheCommandChannel() throws Exception {
        SandboxGatewaySessions sessions = new SandboxGatewaySessions();
        HephHostChannelConfiguration configuration = new HephHostChannelConfiguration(host, sessions);
        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);
        when(registry.addHandler(any(), anyString())).thenReturn(registration);
        configuration.registerWebSocketHandlers(registry);
        ArgumentCaptor<WebSocketHandler> handlers = ArgumentCaptor.forClass(WebSocketHandler.class);
        ArgumentCaptor<HandshakeInterceptor> interceptors = ArgumentCaptor.forClass(HandshakeInterceptor.class);
        verify(registry).addHandler(handlers.capture(), anyString());
        verify(registration).addInterceptors(interceptors.capture());
        HandshakeInterceptor interceptor = interceptors.getValue();
        WebSocketHandler handler = handlers.getValue();

        CompletableFuture<Boolean> ready = starting(READY);
        UUID current = hostId();
        sessions.registerHost(current, TOKEN, Files.createFile(workspace.resolve("input.tar")), "/out");

        Map<String, Object> accepted = handshake(interceptor, current, "Bearer " + TOKEN, 101);
        assertThat(handshake(interceptor, current, "Bearer wrong", 404)).isEmpty();
        assertThat(handshake(interceptor, UUID.randomUUID(), "Bearer " + TOKEN, 404))
                .isEmpty();

        WebSocketSession channel = socket("s1");
        channel.getAttributes().putAll(accepted);
        handler.afterConnectionEstablished(channel);
        assertThat(ready.get(READY.toSeconds(), TimeUnit.SECONDS)).isTrue();
        verify(channel, never()).close(any(CloseStatus.class));

        WebSocketSession second = socket("s2");
        second.getAttributes().putAll(accepted);
        handler.afterConnectionEstablished(second);
        verify(second).close(CloseStatus.POLICY_VIOLATION);

        for (int ping = 0; ping < HephHost.UNANSWERED_PINGS; ping++) {
            host.ping();
        }
        handler.handleMessage(channel, new PongMessage());
        host.ping();
        assertThat(host.isReady())
                .as("a pong through the handler counts as an answer")
                .isTrue();

        handler.afterConnectionClosed(channel, CloseStatus.NORMAL);
        assertThat(lost).hasValue(1);
        assertThat(handshake(interceptor, current, "Bearer " + TOKEN, 404)).isEmpty();
    }

    /** Runs the handshake and returns the attributes it set; {@code status} 101 means it was accepted. */
    private static Map<String, Object> handshake(
            HandshakeInterceptor interceptor, UUID hostId, String authorization, int status) throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/internal/llm/runtime/" + hostId + "/commands");
        request.addHeader("Authorization", authorization);
        MockHttpServletResponse response = new MockHttpServletResponse();
        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(response),
                mock(WebSocketHandler.class),
                attributes);
        assertThat(accepted).isEqualTo(status == 101);
        if (!accepted) {
            assertThat(response.getStatus()).isEqualTo(status);
        }
        return attributes;
    }
}
