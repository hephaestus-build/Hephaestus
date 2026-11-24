package de.tum.cit.aet.hephaestus.agent.proxy;

import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.restclient.RestTemplateCustomizer;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Refuses a model request that leaves the server past the model proxy, which fences, bills and bounds every model
 * call. The server's own HTTP clients come from injected Spring builders ({@code HttpClientConstructionArchTest}, and
 * {@code ModelCallBoundaryArchTest} for OkHttp and Reactor Netty), so these customizers see every request that server
 * code sends. A vendor SDK's own transport, such as Slack's, is outside them.
 *
 * <p>A request is a model request by its shape, wherever it goes: a POST whose JSON body names a model and carries what
 * the model reads, or carries Gemini's {@code contents} for the model that its path names. Only the proxy's own
 * requests carry {@link #PROXY_MARK}. A metadata lookup that names a model alone, such as Ollama's {@code /api/show},
 * asks nothing of it and passes.
 */
@Configuration(proxyBeanMethods = false)
public class ModelEgressConfig {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** How deep a request is searched: a batch entry holds its request a few levels down. */
    private static final int MAX_DEPTH = 6;

    /** The request attribute that carries the proxy's mark. */
    static final String PROXY_CALL = ModelEgressConfig.class.getName() + ".proxyCall";

    /**
     * The proxy's mark: an object that no class outside this package can reach. A caller elsewhere can set the
     * attribute, but not to this object, so it cannot claim the proxy's right to send a model request.
     */
    static final Object PROXY_MARK = new Object();

    /**
     * Whether the request asks a model for an answer. Its declared content type does not decide: a client sends a
     * String body as {@code text/plain} unless told otherwise, and a model reads the JSON all the same.
     */
    static boolean isModelRequest(@Nullable HttpMethod method, byte[] body) {
        if (!HttpMethod.POST.equals(method) || body.length == 0) return false;
        JsonNode request;
        try {
            request = JSON.readTree(body);
        } catch (JacksonException notJson) {
            return false;
        }
        return request != null && asksModel(request, 0);
    }

    /**
     * Whether a JSON value, or a value nested in it, asks a model for an answer. Nested, because a batch carries its
     * requests inside its entries, as Anthropic's Message Batches do.
     */
    private static boolean asksModel(JsonNode node, int depth) {
        if (depth > MAX_DEPTH) return false;
        if (node.isObject()) {
            boolean readsInput = node.has("messages") || node.has("input") || node.has("prompt");
            if ((node.path("model").isString() && readsInput) || node.has("contents")) return true;
        }
        for (JsonNode child : node) if (asksModel(child, depth + 1)) return true;
        return false;
    }

    private static IllegalStateException refused(URI uri) {
        return new IllegalStateException("A model request must go through the model proxy: " + uri.getPath());
    }

    /** No blocking client sends a model request: the proxy streams through its reactive client. */
    @Bean
    RestClientCustomizer modelEgressRestClient() {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            if (isModelRequest(request.getMethod(), body))
                throw refused(request.getURI());
            return execution.execute(request, body);
        });
    }

    @Bean
    RestTemplateCustomizer modelEgressRestTemplate() {
        return template -> template.getInterceptors().add((request, body, execution) -> {
            if (isModelRequest(request.getMethod(), body))
                throw refused(request.getURI());
            return execution.execute(request, body);
        });
    }

    @Bean
    WebClientCustomizer modelEgressWebClient() {
        return builder -> builder.filter(webClientGuard());
    }

    /**
     * Reads a request's body as the client writes it. A model request fails there, before its body leaves: the
     * endpoint may see the request line, but never the prompt.
     */
    static ExchangeFilterFunction webClientGuard() {
        return (request, next) -> {
            if (request.attribute(PROXY_CALL).orElse(null) == PROXY_MARK || !HttpMethod.POST.equals(request.method()))
                return next.exchange(request);
            BodyInserter<?, ? super ClientHttpRequest> body = request.body();
            BodyInserter<Object, ClientHttpRequest> inspected =
                    (message, context) -> body.insert(new Inspected(message, request.url()), context);
            return next.exchange(ClientRequest.from(request).body(inspected).build());
        };
    }

    /** A request whose body is read whole before it is written, so a model request never sends it. */
    private static final class Inspected extends ClientHttpRequestDecorator {
        private final URI url;

        Inspected(ClientHttpRequest delegate, URI url) {
            super(delegate);
            this.url = url;
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return DataBufferUtils.join(body).flatMap(joined -> {
                byte[] bytes = new byte[joined.readableByteCount()];
                joined.read(bytes);
                DataBufferUtils.release(joined);
                if (isModelRequest(HttpMethod.POST, bytes))
                    return Mono.error(refused(url));
                return super.writeWith(Mono.just(bufferFactory().wrap(bytes)));
            });
        }

        /** A body written in flushed parts is read whole too, as one part. */
        @Override
        public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
            return writeWith(Flux.from(body).concatMap(Flux::from));
        }
    }
}
