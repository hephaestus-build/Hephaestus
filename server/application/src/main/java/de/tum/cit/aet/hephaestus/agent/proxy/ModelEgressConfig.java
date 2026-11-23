package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.restclient.RestTemplateCustomizer;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

/**
 * Refuses a model request that leaves the server past the model proxy, which fences, bills and bounds every model
 * call. The server's own HTTP clients come from injected Spring builders ({@code HttpClientConstructionArchTest}, and
 * {@code ModelCallBoundaryArchTest} for OkHttp), so these customizers see every request that server code sends. A
 * vendor SDK's own transport, such as Slack's, is outside them. A request is a model request by what it does: a POST
 * to an inference path. Only the proxy's own requests carry {@link #PROXY_CALL}. Connection setup still reads model
 * listings and limits, which are no inference.
 */
@Configuration(proxyBeanMethods = false)
public class ModelEgressConfig {

    /** The request attribute that carries the proxy's mark. */
    static final String PROXY_CALL = ModelEgressConfig.class.getName() + ".proxyCall";

    /**
     * The proxy's mark: an object that no class outside this package can reach. A caller elsewhere can set the
     * attribute, but not to this object, so it cannot claim the proxy's right to send a model request.
     */
    static final Object PROXY_MARK = new Object();

    /**
     * The paths that ask a model for an answer: those of the chat protocols that the proxy serves, and the other
     * inference paths of the same providers (legacy completions, embeddings, batches, Ollama's native API).
     */
    private static final List<String> INFERENCE_PATHS = Stream.concat(
                    Arrays.stream(LlmApiProtocol.values()).map(LlmApiProtocol::upstreamPath),
                    Stream.of("/completions", "/embeddings", "/messages/batches", "/api/chat", "/api/generate"))
            .toList();

    /** Whether the request asks a model for an answer, whole path segments compared and a trailing slash ignored. */
    static boolean isModelRequest(HttpMethod method, URI uri) {
        String path = uri.getPath();
        if (!HttpMethod.POST.equals(method) || path == null) return false;
        String segments = "/" + path.replaceAll("^/+|/+$", "");
        return INFERENCE_PATHS.stream().anyMatch(inference -> segments.endsWith(inference));
    }

    private static IllegalStateException refused(URI uri) {
        return new IllegalStateException("A model request must go through the model proxy: " + uri.getPath());
    }

    /** No blocking client sends a model request: the proxy streams through its reactive client. */
    @Bean
    RestClientCustomizer modelEgressRestClient() {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            if (isModelRequest(request.getMethod(), request.getURI())) throw refused(request.getURI());
            return execution.execute(request, body);
        });
    }

    @Bean
    RestTemplateCustomizer modelEgressRestTemplate() {
        return template -> template.getInterceptors().add((request, body, execution) -> {
            if (isModelRequest(request.getMethod(), request.getURI())) throw refused(request.getURI());
            return execution.execute(request, body);
        });
    }

    @Bean
    WebClientCustomizer modelEgressWebClient() {
        return builder -> builder.filter(ExchangeFilterFunction.ofRequestProcessor(ModelEgressConfig::admit));
    }

    static Mono<ClientRequest> admit(ClientRequest request) {
        boolean proxy = request.attribute(PROXY_CALL).orElse(null) == PROXY_MARK;
        return proxy || !isModelRequest(request.method(), request.url())
                ? Mono.just(request)
                : Mono.error(refused(request.url()));
    }
}
