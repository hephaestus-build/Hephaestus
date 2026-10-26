package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.LlmProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.EgressPolicy;
import de.tum.cit.aet.hephaestus.agent.catalog.InstanceLlmSettingsRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

@Timeout(15)
class LlmProxyWebClientConfigTest extends BaseUnitTest {

    private static final String LOOPBACK_URL = "http://localhost:9/";
    private static final Duration BLOCK = Duration.ofSeconds(5);

    private static EgressPolicy egressPolicy(boolean allowLoopback) {
        return new EgressPolicy(
                mock(InstanceLlmSettingsRepository.class),
                new LlmProperties(
                        "",
                        new LlmProperties.Egress(allowLoopback, List.of(), false),
                        new LlmProperties.Fx(LlmProperties.ECB_DAILY_URL)));
    }

    @Test
    void proxyWebClientRejectsLoopbackResolvingHostWhenLoopbackNotAllowed() {
        LlmProxyWebClientConfig config = new LlmProxyWebClientConfig();
        ConnectionProvider provider = config.llmProxyConnectionProvider();
        LoopResources loop = config.llmProxyLoopResources();
        try {
            WebClient client = config.llmProxyWebClient(provider, loop, egressPolicy(false), WebClient.builder());

            Throwable thrown = catchThrowable(() -> client.get()
                    .uri(LOOPBACK_URL)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(BLOCK));

            assertThat(thrown).isNotNull();

            Throwable root = NestedExceptionUtils.getMostSpecificCause(thrown);
            assertThat(root)
                    .as("guard must reject at DNS resolution, not surface a plain connect failure")
                    .isInstanceOf(UnknownHostException.class)
                    .isNotInstanceOf(ConnectException.class);
            assertThat(root.getMessage()).contains("SSRF guard");
        } finally {
            provider.dispose();
            loop.disposeLater().block(BLOCK);
        }
    }

    /** Only the base URL is egress-validated, so a redirect must reach the caller unfollowed. */
    @Test
    void shouldNotFollowARedirectFromTheProvider() {
        AtomicInteger redirected = new AtomicInteger();
        DisposableServer server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes.get(
                                "/models",
                                (request, response) -> response.status(302)
                                        .header("Location", "/internal")
                                        .send())
                        .get("/internal", (request, response) -> {
                            redirected.incrementAndGet();
                            return response.sendString(Mono.just("secret"));
                        }))
                .bindNow(BLOCK);
        LlmProxyWebClientConfig config = new LlmProxyWebClientConfig();
        ConnectionProvider provider = config.llmProxyConnectionProvider();
        LoopResources loop = config.llmProxyLoopResources();
        try {
            WebClient client = config.llmProxyWebClient(provider, loop, egressPolicy(true), WebClient.builder());

            var response = client.get()
                    .uri("http://127.0.0.1:" + server.port() + "/models")
                    .retrieve()
                    .onStatus(status -> true, ignored -> Mono.empty())
                    .toBodilessEntity()
                    .block(BLOCK);

            assertThat(response).isNotNull();
            assertThat(response.getStatusCode().value()).isEqualTo(302);
            assertThat(redirected).hasValue(0);
        } finally {
            server.disposeNow();
            provider.dispose();
            loop.disposeLater().block(BLOCK);
        }
    }
}
