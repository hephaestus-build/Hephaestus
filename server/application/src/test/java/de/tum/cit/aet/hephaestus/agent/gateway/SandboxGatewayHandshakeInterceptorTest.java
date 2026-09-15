package de.tum.cit.aet.hephaestus.agent.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

@Tag("unit")
class SandboxGatewayHandshakeInterceptorTest {
    @TempDir
    Path temporary;

    private final SandboxGatewaySessions sessions = new SandboxGatewaySessions();
    private final SandboxGatewayHandshakeInterceptor interceptor = new SandboxGatewayHandshakeInterceptor(sessions);

    @Test
    void shouldBindTheInteractiveChannelWhenTheCredentialMatchesTheSession() throws Exception {
        try (var session = sessions.register("token", Files.writeString(temporary.resolve("in.tar"), ""), "out")) {
            var channel = session.enableInteractive(1024);
            var attributes = new HashMap<String, Object>();
            var response = new MockHttpServletResponse();

            assertThat(handshake(session.id().toString(), "Bearer token", response, attributes))
                    .isTrue();

            assertThat(attributes).containsEntry(SandboxGatewayHandshakeInterceptor.CHANNEL, channel);
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        }
    }

    @Test
    void shouldAnswerNotFoundWithoutAStackTraceWhenTheSessionIdIsNotAUuid() throws Exception {
        var response = new MockHttpServletResponse();

        assertThat(handshake("not-a-uuid", "Bearer token", response, new HashMap<>()))
                .isFalse();

        assertThat(response.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void shouldAnswerNotFoundWhenTheCredentialDoesNotMatch() throws Exception {
        try (var session = sessions.register("token", Files.writeString(temporary.resolve("in.tar"), ""), "out")) {
            session.enableInteractive(1024);
            var response = new MockHttpServletResponse();

            assertThat(handshake(session.id().toString(), "Bearer other", response, new HashMap<>()))
                    .isFalse();

            assertThat(response.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        }
    }

    @Test
    void shouldAnswerNotFoundWhenTheAuthorizationHeaderIsMissing() throws Exception {
        try (var session = sessions.register("token", Files.writeString(temporary.resolve("in.tar"), ""), "out")) {
            session.enableInteractive(1024);
            var response = new MockHttpServletResponse();

            assertThat(handshake(session.id().toString(), null, response, new HashMap<>()))
                    .isFalse();

            assertThat(response.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        }
    }

    @Test
    void shouldAnswerNotFoundWhenTheSessionIsNotInteractive() throws Exception {
        try (var session = sessions.register("token", Files.writeString(temporary.resolve("in.tar"), ""), "out")) {
            var response = new MockHttpServletResponse();

            assertThat(handshake(session.id().toString(), "Bearer token", response, new HashMap<>()))
                    .isFalse();

            assertThat(response.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        }
    }

    @Test
    void shouldAnswerNotFoundWhenTheSessionIsClosed() throws Exception {
        var session = sessions.register("token", Files.writeString(temporary.resolve("in.tar"), ""), "out");
        session.enableInteractive(1024);
        session.close();
        var response = new MockHttpServletResponse();

        assertThat(handshake(session.id().toString(), "Bearer token", response, new HashMap<>()))
                .isFalse();

        assertThat(response.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    private boolean handshake(
            String id,
            @Nullable String authorization,
            MockHttpServletResponse response,
            Map<String, Object> attributes) {
        var request = new MockHttpServletRequest("GET", "/internal/llm/runtime/" + id + "/frames");
        if (authorization != null) request.addHeader("Authorization", authorization);
        var servletResponse = new ServletServerHttpResponse(response);
        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(request), servletResponse, mock(WebSocketHandler.class), attributes);
        servletResponse.close();
        return accepted;
    }
}
