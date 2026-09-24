package de.tum.cit.aet.hephaestus.integration.outline.client;

import de.tum.cit.aet.hephaestus.core.WebClientConnectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shared WebClient for the Outline REST client. Everything about it is <em>transport policy</em>, which
 * is exactly why it is hand-written while the response bodies it decodes are generated from Outline's OpenAPI
 * spec:
 *
 * <ul>
 *   <li><b>SSRF guard.</b> The server URL is admin-supplied, so the connector is
 *       {@link WebClientConnectors#ssrfGuarded()} — the same DNS-rebind-closing resolver GitLab preflight
 *       uses (ADR 0023 §4). {@link OutlineApiClient} additionally validates the URL up front with
 *       {@code ServerUrlValidator}.</li>
 *   <li><b>Tolerant reader.</b> Generated models cannot carry {@code @JsonIgnoreProperties}, and Outline
 *       returns far more fields than we map, so the decoder disables {@code FAIL_ON_UNKNOWN_PROPERTIES}.
 *       Unknown-field tolerance is decoder configuration, not an annotation — pinned by
 *       {@code OutlineDeserializationToleranceTest}.</li>
 *   <li><b>Rate-limit capture.</b> An exchange filter reads Outline's {@code Retry-After} /
 *       {@code RateLimit-*} response headers into {@link OutlineRateLimitTracker}, keyed by server origin,
 *       so the admin page can surface an Outline rate-limit diagnostic. Outline emits those headers
 *       <em>only on a 429</em>, so in practice this filter records throttle events and nothing else — the
 *       tracker infers the throttle from their presence, which is why no status needs threading through
 *       here. The {@code Retry-After}-honoring 429 retry stays in {@link OutlineApiClient}; this filter
 *       only observes.</li>
 * </ul>
 *
 * Resilience (circuit breaker + bounded retry) lives in {@link OutlineResilienceConfig}; this bean carries no
 * retry filter because {@link OutlineApiClient} wraps every call in the Resilience4j decorators instead.
 */
@Configuration
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
public class OutlineClientConfig {

    /** Outline document exports (Markdown bodies) can be large; lift the 256 KB default buffer ceiling. */
    private static final int MAX_BUFFER_SIZE = 16 * 1024 * 1024;

    private final OutlineRateLimitTracker rateLimitTracker;

    public OutlineClientConfig(OutlineRateLimitTracker rateLimitTracker) {
        this.rateLimitTracker = rateLimitTracker;
    }

    /**
     * The Outline client's tolerant reader, derived from the Boot-configured mapper so it inherits every
     * module (java.time, etc.) and flips only the one tolerance knob the generated vendor models need:
     *
     * <ul>
     *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES=false} — Outline returns far more fields than we map, and a
     *       generated model cannot carry {@code @JsonIgnoreProperties}. Pinned by
     *       {@code OutlineDeserializationToleranceTest}.</li>
     * </ul>
     *
     * <p>Unknown <em>enum</em> values are tolerated separately, at the contract level (openapi-generator's
     * {@code enumUnknownDefaultCase=true} in {@code server/generated-clients/build.gradle.kts}), not here — full rationale in
     * {@code OutlineDeserializationToleranceTest#unknownEnumValueDecodesToUnknownDefaultOnWebClientPath}.
     *
     * <p>Exposed so tests that deserialize real fixtures exercise the exact same policy as the running client.
     */
    public static JsonMapper tolerantMapper(JsonMapper base) {
        return base.rebuild()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build();
    }

    @Bean
    @Qualifier("outlineWebClient")
    public WebClient outlineWebClient(JsonMapper baseObjectMapper) {
        JsonMapper tolerantMapper = tolerantMapper(baseObjectMapper);

        JacksonJsonDecoder decoder = new JacksonJsonDecoder(tolerantMapper);
        decoder.setMaxInMemorySize(MAX_BUFFER_SIZE);

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(config -> {
                    config.defaultCodecs().maxInMemorySize(MAX_BUFFER_SIZE);
                    config.customCodecs().register(new JacksonJsonEncoder(tolerantMapper));
                    config.customCodecs().register(decoder);
                })
                .build();

        return WebClient.builder()
                .clientConnector(WebClientConnectors.ssrfGuarded())
                .exchangeStrategies(strategies)
                .filter(rateLimitTrackingFilter())
                .build();
    }

    /**
     * Records Outline's {@code RateLimit-*} response headers into the tracker, keyed by the request's server
     * origin. A non-blocking {@code doOnNext} side effect — it never alters the response or fails the call.
     */
    private ExchangeFilterFunction rateLimitTrackingFilter() {
        return (request, next) -> next.exchange(request)
                .doOnNext(response -> rateLimitTracker.updateFromHeaders(
                        OutlineRateLimitTracker.scopeOf(request.url().toString()),
                        response.headers().asHttpHeaders()));
    }
}
