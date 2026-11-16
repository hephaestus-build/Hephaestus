package de.tum.cit.aet.hephaestus.core.auth.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.WebClientConnectors;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** The limits on reading a client ID metadata document (CIMD § 6). */
class HttpClientMetadataSourceTest extends BaseUnitTest {

    private static final String DOCUMENT = "{\"client_id\":\"https://client.example/metadata\"}";

    private DisposableServer peer;

    @BeforeEach
    void serve() {
        peer = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes.get(
                                "/metadata", (request, response) -> response.sendString(Mono.just(DOCUMENT)))
                        .get("/moved", (request, response) -> response.sendRedirect("/metadata"))
                        .get(
                                "/large",
                                (request, response) -> response.sendString(
                                        Mono.just("x".repeat(HttpClientMetadataSource.MAX_DOCUMENT_BYTES + 1))))
                        .get("/slow", (request, response) -> Mono.never()))
                .bindNow();
    }

    @AfterEach
    void stop() {
        peer.disposeNow();
    }

    /** The limits under test, with loopback let through so the local peer is reachable. */
    private HttpClientMetadataSource local() {
        return new HttpClientMetadataSource(
                WebClient.builder(), WebClientConnectors.ssrfGuarded(true), Duration.ofSeconds(2));
    }

    private URI at(String path) {
        return URI.create("http://127.0.0.1:" + peer.port() + path);
    }

    @Test
    void shouldReadADocumentWithinTheLimits() {
        assertThat(local().fetch(at("/metadata"))).contains(DOCUMENT);
    }

    @Test
    void shouldNotFollowARedirect() {
        assertThat(local().fetch(at("/moved"))).isEmpty();
    }

    @Test
    void shouldRefuseADocumentOverFiveKilobytes() {
        assertThat(local().fetch(at("/large"))).isEmpty();
    }

    @Test
    @Timeout(20)
    void shouldGiveUpOnAPeerThatDoesNotAnswer() {
        assertThat(local().fetch(at("/slow"))).isEmpty();
    }

    @Test
    void shouldRefuseALoopbackOrPrivateAddressAsShipped() {
        HttpClientMetadataSource shipped = new HttpClientMetadataSource(WebClient.builder());

        assertThat(shipped.fetch(at("/metadata"))).isEmpty();
        assertThat(shipped.fetch(URI.create("https://10.0.0.1/metadata"))).isEmpty();
    }
}
