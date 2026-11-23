package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/** The server's own clients, as the application builds them, cannot reach a model past the proxy. */
class ModelEgressGuardTest extends BaseUnitTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations.of(RestClientAutoConfiguration.class, WebClientAutoConfiguration.class))
            .withUserConfiguration(ModelEgressGuard.class);

    private MockWebServer model;

    @BeforeEach
    void startModel() throws IOException {
        model = new MockWebServer();
        model.start();
    }

    @AfterEach
    void stopModel() {
        model.close();
    }

    /** A bean that asks a keyless model endpoint for an answer with the injected builder, past the proxy. */
    record OwnModelClient(RestClient.Builder clients) {
        @Nullable
        String ask(String baseUrl, String path) {
            return clients.build()
                    .post()
                    .uri(baseUrl + path)
                    .body("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                    .retrieve()
                    .body(String.class);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"/v1/chat/completions", "/v1/messages", "/v1/responses", "/v1/chat/completions/", "/api/chat"})
    void shouldRefuseAModelRequestThatABlockingClientSendsPastTheProxy(String path) {
        model.enqueue(new MockResponse.Builder().body("{}").build());
        context.run(app -> {
            var client = new OwnModelClient(app.getBean(RestClient.Builder.class));

            assertThatThrownBy(() -> client.ask(model.url("/").toString(), path.substring(1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("A model request must go through the model proxy: ");
            assertThat(model.getRequestCount()).isZero();
        });
    }

    @Test
    void shouldRefuseAModelRequestThatAReactiveClientSendsWithoutTheProxysMark() {
        model.enqueue(new MockResponse.Builder().body("{}").build());
        context.run(app -> {
            var client = app.getBean(WebClient.Builder.class).build();

            assertThatThrownBy(() -> client.post()
                            .uri(model.url("/chat/completions").uri())
                            .bodyValue("{}")
                            .retrieve()
                            .bodyToMono(String.class)
                            .block())
                    .hasMessageContaining("A model request must go through the model proxy");
            assertThat(model.getRequestCount()).isZero();
        });
    }

    @Test
    void shouldLetTheProxysOwnModelRequestAndConnectionLookupsThrough() {
        model.enqueue(new MockResponse.Builder().body("{\"data\":[]}").build());
        model.enqueue(new MockResponse.Builder().body("{}").build());
        context.run(app -> {
            var lookup = app.getBean(RestClient.Builder.class)
                    .build()
                    .get()
                    .uri(model.url("/v1/models").uri())
                    .retrieve()
                    .body(String.class);
            var proxied = app.getBean(WebClient.Builder.class)
                    .build()
                    .post()
                    .uri(model.url("/chat/completions").uri())
                    .attribute(ModelEgressGuard.PROXY_CALL, true)
                    .bodyValue("{}")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            assertThat(lookup).isEqualTo("{\"data\":[]}");
            assertThat(proxied).isEqualTo("{}");
            assertThat(model.getRequestCount()).isEqualTo(2);
        });
    }
}
