package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@RequiredArgsConstructor
@ConditionalOnProperty(name = RuntimeRole.WORKER_PROPERTY, havingValue = "true", matchIfMissing = true)
public class SandboxGatewayWebSocketConfiguration implements WebSocketConfigurer {
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
                        SandboxGatewayHandshakeInterceptor.PATH.replace("{id}", "*"))
                .addInterceptors(new SandboxGatewayHandshakeInterceptor(sessions));
    }

    private static GatewayInteractiveChannel channel(WebSocketSession socket) {
        if (socket.getAttributes().get(SandboxGatewayHandshakeInterceptor.CHANNEL)
                instanceof GatewayInteractiveChannel channel) {
            return channel;
        }
        throw new IllegalStateException("Missing authenticated interactive channel");
    }
}
