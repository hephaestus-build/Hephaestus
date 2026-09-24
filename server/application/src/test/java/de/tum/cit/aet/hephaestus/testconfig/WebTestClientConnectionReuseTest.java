package de.tum.cit.aet.hephaestus.testconfig;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;

class WebTestClientConnectionReuseTest extends BaseUnitTest {

    @Test
    void shouldReleaseDiscardedBodiesBeforeTheNextRequest() {
        // A small response can be buffered completely and hide a missing body subscription.
        var server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> response.sendString(Mono.just("x".repeat(100_000))))
                .bindNow();
        var connections = ConnectionProvider.builder("response-cleanup-test")
                .maxConnections(1)
                .pendingAcquireTimeout(Duration.ofSeconds(5))
                .build();
        try {
            var client = WebTestClient.bindToServer(new ReactorClientHttpConnector(HttpClient.create(connections)))
                    .baseUrl("http://127.0.0.1:" + server.port())
                    .responseTimeout(Duration.ofSeconds(10))
                    .build();

            for (int request = 0; request < 2; request++) {
                client.get().uri("/").exchange().expectStatus().isOk().expectBody(Void.class);
            }
        } finally {
            try {
                connections.disposeLater().block(Duration.ofSeconds(5));
            } finally {
                server.disposeNow();
            }
        }
    }
}
