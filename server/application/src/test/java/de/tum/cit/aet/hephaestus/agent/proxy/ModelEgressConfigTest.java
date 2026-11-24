package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The server's own clients, as the application builds them, cannot ask a model for an answer past the proxy. */
class ModelEgressConfigTest extends BaseUnitTest {

    private static final String CHAT =
            "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"the prompt\"}]}";

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RestClientAutoConfiguration.class,
                    RestTemplateAutoConfiguration.class,
                    WebClientAutoConfiguration.class))
            .withUserConfiguration(ModelEgressConfig.class);

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
        String ask(String url, String body) {
            return clients.build()
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        }
    }

    /** The requests that ask a model for an answer, also on paths that no list names. */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "/v1/chat/completions | {\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"the prompt\"}]}",
                "/v1/responses | {\"model\":\"m\",\"input\":\"the prompt\"}",
                "/v2/chat | {\"model\":\"command\",\"messages\":[{\"role\":\"user\",\"content\":\"the prompt\"}]}",
                "/v1/fim/completions | {\"model\":\"codestral\",\"prompt\":\"the prompt\"}",
                "/v1beta/models/gemini-pro:generateContent | {\"contents\":[{\"parts\":[{\"text\":\"the prompt\"}]}]}",
                "/v1/messages/batches | {\"requests\":[{\"custom_id\":\"a\",\"params\":{\"model\":\"m\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"the prompt\"}]}}]}",
            })
    void shouldRefuseAModelRequestThatABlockingClientSendsPastTheProxy(String path, String body) {
        model.enqueue(new MockResponse.Builder().body("{}").build());
        context.run(app -> {
            var client = new OwnModelClient(app.getBean(RestClient.Builder.class));

            assertThatThrownBy(() -> client.ask(model.url(path).toString(), body))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("A model request must go through the model proxy: ");
            assertThat(model.getRequestCount()).isZero();
        });
    }

    @Test
    void shouldRefuseAModelRequestThatARestTemplateSendsPastTheProxy() {
        model.enqueue(new MockResponse.Builder().body("{}").build());
        context.run(app -> {
            var template = app.getBean(RestTemplateBuilder.class).build();
            var headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            assertThatThrownBy(() -> template.postForObject(
                            model.url("/anything").uri(), new HttpEntity<>(CHAT, headers), String.class))
                    .hasMessageContaining("A model request must go through the model proxy");
            assertThat(model.getRequestCount()).isZero();
        });
    }

    @Test
    void shouldNeverSendThePromptOfAReactiveModelRequestWithoutTheProxysMark() {
        model.enqueue(new MockResponse.Builder().body("{}").build());
        model.enqueue(new MockResponse.Builder().body("{}").build());
        context.run(app -> {
            var client = app.getBean(WebClient.Builder.class).build();

            assertThatThrownBy(() -> post(client, null))
                    .hasMessageContaining("A model request must go through the model proxy");
            // Another caller can set the attribute, but not to the proxy's mark.
            assertThatThrownBy(() -> post(client, true))
                    .hasMessageContaining("A model request must go through the model proxy");
            // A body written in flushed parts is read whole before it leaves.
            assertThatThrownBy(() -> client.post()
                            .uri(model.url("/stream").uri())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body((message, context) -> message.writeAndFlushWith(Flux.just(
                                    Mono.just(message.bufferFactory()
                                            .wrap(CHAT.substring(0, 20).getBytes(StandardCharsets.UTF_8))),
                                    Mono.just(message.bufferFactory()
                                            .wrap(CHAT.substring(20).getBytes(StandardCharsets.UTF_8))))))
                            .retrieve()
                            .bodyToMono(String.class)
                            .block())
                    .hasMessageContaining("A model request must go through the model proxy");
            assertThat(promptsReceived()).isZero();
        });
    }

    @Test
    void shouldLetTheProxysOwnModelRequestAndConnectionLookupsThrough() {
        model.enqueue(new MockResponse.Builder().body("{\"data\":[]}").build());
        model.enqueue(new MockResponse.Builder().body("{\"model_info\":{}}").build());
        model.enqueue(new MockResponse.Builder().body("{}").build());
        context.run(app -> {
            var blocking = app.getBean(RestClient.Builder.class).build();
            var listing =
                    blocking.get().uri(model.url("/v1/models").uri()).retrieve().body(String.class);
            // Ollama's metadata lookup names a model and asks nothing of it.
            var shown = blocking.post()
                    .uri(model.url("/api/show").uri())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"model\":\"m\"}")
                    .retrieve()
                    .body(String.class);
            var proxied = post(app.getBean(WebClient.Builder.class).build(), ModelEgressConfig.PROXY_MARK);

            assertThat(listing).isEqualTo("{\"data\":[]}");
            assertThat(shown).isEqualTo("{\"model_info\":{}}");
            assertThat(proxied).isEqualTo("{}");
            assertThat(model.getRequestCount()).isEqualTo(3);
        });
    }

    private @Nullable String post(WebClient client, @Nullable Object mark) {
        var request = client.post().uri(model.url("/v1/chat/completions").uri());
        if (mark != null) request = request.attribute(ModelEgressConfig.PROXY_CALL, mark);
        return request.contentType(MediaType.APPLICATION_JSON)
                .bodyValue(CHAT)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    /** The requests that reached the model with their prompt; a refused one may have sent its request line only. */
    private int promptsReceived() throws InterruptedException {
        int prompts = 0;
        for (RecordedRequest request; (request = model.takeRequest(200, TimeUnit.MILLISECONDS)) != null; ) {
            var body = request.getBody();
            if (body != null && body.utf8().contains("the prompt")) prompts++;
        }
        return prompts;
    }
}
