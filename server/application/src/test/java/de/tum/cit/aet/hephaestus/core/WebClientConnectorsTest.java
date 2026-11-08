package de.tum.cit.aet.hephaestus.core;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.netty.handler.timeout.ReadTimeoutException;
import java.time.Duration;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

class WebClientConnectorsTest extends BaseUnitTest {
    @Test
    @Timeout(45)
    void shouldEndRequestsWhenThePeerStopsBeforeOrAfterHeaders() {
        var peer = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> request.uri().equals("/body")
                        ? response.chunkedTransfer(true).sendHeaders().then(Mono.never())
                        : Mono.never())
                .bindNow();
        try {
            var client = WebClient.builder()
                    .clientConnector(WebClientConnectors.ssrfGuarded(true))
                    .baseUrl("http://127.0.0.1:" + peer.port())
                    .build();

            var beforeHeaders = client.get()
                    .uri("/headers")
                    .retrieve()
                    .bodyToMono(String.class)
                    .materialize();
            var afterHeaders = client.get()
                    .uri("/body")
                    .retrieve()
                    .bodyToMono(String.class)
                    .materialize();
            var failures =
                    Objects.requireNonNull(Mono.zip(beforeHeaders, afterHeaders).block(Duration.ofSeconds(35)));

            assertThat(failures.getT1().getThrowable()).hasRootCauseInstanceOf(ReadTimeoutException.class);
            assertThat(failures.getT2().getThrowable()).hasRootCauseInstanceOf(ReadTimeoutException.class);
        } finally {
            peer.disposeNow();
        }
    }
}
