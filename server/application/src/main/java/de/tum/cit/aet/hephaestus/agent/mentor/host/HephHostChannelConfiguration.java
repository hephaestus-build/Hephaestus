package de.tum.cit.aet.hephaestus.agent.mentor.host;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriTemplate;

/**
 * The Hephaestus host's command channel: one WebSocket the host opens to its own gateway session, at
 * {@code /internal/llm/runtime/{id}/commands}, over which this worker sends it {@code start}, {@code stop} and pings
 * ({@link HephHost#ping}). Only the host that runs now may connect, once, with the token its session was registered
 * with. The gateway's token filter refuses a handshake with another token ({@code 401}); a session token of a host
 * that does not run now gets a bare {@code 404}; a second channel of the current host is closed with
 * {@code POLICY_VIOLATION}.
 */
@EnableWebSocket
public class HephHostChannelConfiguration implements WebSocketConfigurer {

    static final String PATH = "/internal/llm/runtime/{id}/commands";

    private static final Logger log = LoggerFactory.getLogger(HephHostChannelConfiguration.class);
    private static final UriTemplate TEMPLATE = new UriTemplate(PATH);
    private static final String HOST_ID = "heph.host.id";

    private final HephHost host;
    private final SandboxGatewaySessions sessions;

    public HephHostChannelConfiguration(HephHost host, SandboxGatewaySessions sessions) {
        this.host = host;
        this.sessions = sessions;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new Handler(), PATH.replace("{id}", "*")).addInterceptors(new Interceptor());
    }

    private final class Interceptor implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(
                ServerHttpRequest request,
                ServerHttpResponse response,
                WebSocketHandler handler,
                Map<String, Object> attributes) {
            String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            String id = TEMPLATE.match(request.getURI().getPath()).get("id");
            try {
                UUID hostId = UUID.fromString(id == null ? "" : id);
                if (authorization != null
                        && sessions.require(hostId, authorization).id().equals(hostId)
                        && host.isCurrent(hostId)) {
                    attributes.put(HOST_ID, hostId);
                    return true;
                }
            } catch (RuntimeException refused) {
                // The class only: the message can quote the path or the credential the caller sent.
                log.debug(
                        "Refused a Hephaestus host command channel: {}",
                        refused.getClass().getSimpleName());
            }
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

    private final class Handler extends TextWebSocketHandler {
        @Override
        public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
            if (!(socket.getAttributes().get(HOST_ID) instanceof UUID hostId) || !host.connected(hostId, socket)) {
                socket.close(CloseStatus.POLICY_VIOLATION);
            }
        }

        /** The host sends nothing on this channel; its turns talk to the worker over HTTP with their credential. */
        @Override
        protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
            log.debug("Ignoring a frame from the Hephaestus host");
        }

        @Override
        protected void handlePongMessage(WebSocketSession socket, PongMessage message) {
            host.ponged(socket);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
            host.disconnected(socket);
        }
    }
}
