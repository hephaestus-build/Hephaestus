package de.tum.cit.aet.hephaestus.agent.gateway;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriTemplate;

/**
 * Binds a frame-channel upgrade to its registered session. Every rejection is a bare 404: the
 * sandbox holding a wrong credential learns nothing about which session ids exist.
 */
@RequiredArgsConstructor
final class SandboxGatewayHandshakeInterceptor implements HandshakeInterceptor {
    static final String PATH = "/internal/llm/runtime/{id}/frames";
    static final String CHANNEL = "sandbox.gateway.channel";
    private static final UriTemplate TEMPLATE = new UriTemplate(PATH);
    private final SandboxGatewaySessions sessions;

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler handler,
            Map<String, Object> attributes) {
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        String id = TEMPLATE.match(request.getURI().getPath()).get("id");
        if (authorization == null || id == null) return reject(response);
        try {
            attributes.put(
                    CHANNEL,
                    sessions.require(UUID.fromString(id), authorization).interactive());
            return true;
        } catch (RuntimeException e) {
            return reject(response);
        }
    }

    private static boolean reject(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.NOT_FOUND);
        return false;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler handler,
            @Nullable Exception exception) {}
}
