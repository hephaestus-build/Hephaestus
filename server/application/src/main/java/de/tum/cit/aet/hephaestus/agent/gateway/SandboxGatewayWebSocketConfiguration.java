package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@RequiredArgsConstructor
@ConditionalOnProperty(name = RuntimeRole.WORKER_PROPERTY, havingValue = "true", matchIfMissing = true)
public class SandboxGatewayWebSocketConfiguration implements WebSocketConfigurer {
    private static final String CHANNEL = "sandbox.gateway.channel";
    private static final String CONNECTED = "sandbox.gateway.connected";
    private final SandboxGatewaySessions sessions;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(
                        new TextWebSocketHandler() {
                            @Override
                            public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
                                if (channel(socket).connect(socket)) {
                                    socket.getAttributes().put(CONNECTED, true);
                                } else {
                                    socket.close(CloseStatus.POLICY_VIOLATION);
                                }
                            }

                            @Override
                            protected void handleTextMessage(WebSocketSession socket, TextMessage message)
                                    throws Exception {
                                if (!Boolean.TRUE.equals(socket.getAttributes().get(CONNECTED))) {
                                    socket.close(CloseStatus.POLICY_VIOLATION);
                                    return;
                                }
                                channel(socket).receive(message);
                            }

                            @Override
                            public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
                                if (Boolean.TRUE.equals(socket.getAttributes().get(CONNECTED))) {
                                    int code = status.getCode();
                                    channel(socket).finish(code >= 4000 && code <= 4255 ? code - 4000 : 1);
                                }
                            }
                        },
                        "/internal/llm/runtime/*/frames")
                .addInterceptors(new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(
                            ServerHttpRequest request,
                            ServerHttpResponse response,
                            WebSocketHandler handler,
                            Map<String, Object> attributes) {
                        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
                        if (authorization == null) {
                            return false;
                        }
                        String path = request.getURI().getPath();
                        UUID id = UUID.fromString(
                                path.substring("/internal/llm/runtime/".length(), path.length() - "/frames".length()));
                        attributes.put(
                                CHANNEL, sessions.require(id, authorization).interactive());
                        return true;
                    }

                    @Override
                    public void afterHandshake(
                            ServerHttpRequest request,
                            ServerHttpResponse response,
                            WebSocketHandler handler,
                            @Nullable Exception exception) {}
                });
    }

    private static GatewayInteractiveChannel channel(WebSocketSession socket) {
        if (socket.getAttributes().get(CHANNEL) instanceof GatewayInteractiveChannel channel) {
            return channel;
        }
        throw new IllegalStateException("Missing authenticated interactive channel");
    }
}
