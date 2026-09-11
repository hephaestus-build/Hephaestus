package de.tum.cit.aet.hephaestus.agent.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

@Tag("unit")
class GatewayInteractiveChannelTest {
    @Test
    void shouldExchangeJsonlFramesWithoutDockerExec() throws Exception {
        var sent = new LinkedBlockingQueue<WebSocketMessage<?>>();
        var socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        when(socket.getId()).thenReturn("runtime");
        doAnswer(invocation -> {
                    sent.add(invocation.getArgument(0));
                    return null;
                })
                .when(socket)
                .sendMessage(any());
        try (var channel = new GatewayInteractiveChannel(1024, 1000)) {
            assertThat(channel.connect(socket)).isTrue();
            channel.stdin().write("{\"type\":\"prompt\"}\n".getBytes(StandardCharsets.UTF_8));
            var command = sent.poll(5, TimeUnit.SECONDS);
            assertThat(command).isInstanceOf(TextMessage.class);
            assertThat(command.getPayload()).isEqualTo("{\"type\":\"prompt\"}");
            channel.receive(new TextMessage("{\"type\":\"ready\"}"));
            assertThat(new BufferedReader(channel.stdout()).readLine()).isEqualTo("{\"type\":\"ready\"}");
            assertThat(channel.connect(mock(WebSocketSession.class))).isFalse();
        }
    }

    @Test
    void shouldDrainFinalFrameBeforeReportingEndOfStream() throws Exception {
        try (var channel = new GatewayInteractiveChannel(1024, 1000)) {
            channel.receive(new TextMessage("final"));
            channel.finish(42);
            var reader = new BufferedReader(channel.stdout());
            assertThat(reader.readLine()).isEqualTo("final");
            assertThat(reader.readLine()).isNull();
            assertThat(channel.waitFor(Duration.ofMillis(10))).isTrue();
            assertThat(channel.exitValueOrAlive()).isEqualTo(42);
            assertThat(channel.connect(mock(WebSocketSession.class))).isFalse();
        }
    }

    @Test
    void shouldRejectOversizedAndClosedInputWithoutAcceptingPartialFrames() throws Exception {
        try (var channel = new GatewayInteractiveChannel(4, 1000)) {
            assertThatIOException().isThrownBy(() -> channel.receive(new TextMessage("12345")));
            assertThatIOException().isThrownBy(() -> channel.receive(new TextMessage("€€")));
            channel.receive(new TextMessage("ok"));
            channel.finish(0);
            assertThatIOException().isThrownBy(() -> channel.receive(new TextMessage("late")));
            var reader = new BufferedReader(channel.stdout());
            assertThat(reader.readLine()).isEqualTo("ok");
            assertThat(reader.readLine()).isNull();
        }
    }

    @Test
    void shouldWakeWaitersWhenClosedBeforeConnection() throws Exception {
        var channel = new GatewayInteractiveChannel(1024, 1000);
        assertThat(channel.waitFor(Duration.ZERO)).isFalse();
        channel.close();
        assertThat(channel.waitFor(Duration.ofMillis(10))).isTrue();
        assertThat(channel.exitValueOrAlive()).isEqualTo(1);
        channel.close();
    }
}
