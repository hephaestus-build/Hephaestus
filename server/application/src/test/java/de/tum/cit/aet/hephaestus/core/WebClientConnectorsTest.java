package de.tum.cit.aet.hephaestus.core;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.netty.handler.timeout.ReadTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

class WebClientConnectorsTest extends BaseUnitTest {
    @Test
    @Timeout(45)
    void shouldEndARequestWhenItsPeerStopsResponding() {
        var peer = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> Mono.never())
                .bindNow();
        try {
            var client = WebClient.builder()
                    .clientConnector(WebClientConnectors.ssrfGuarded(true))
                    .baseUrl("http://127.0.0.1:" + peer.port())
                    .build();

            assertThatThrownBy(() ->
                            client.get().retrieve().bodyToMono(String.class).block(Duration.ofSeconds(35)))
                    .hasRootCauseInstanceOf(ReadTimeoutException.class);
        } finally {
            peer.disposeNow();
        }
    }
}
