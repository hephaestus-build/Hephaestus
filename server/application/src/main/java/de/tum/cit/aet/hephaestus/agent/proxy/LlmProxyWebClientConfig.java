package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.LlmProperties;
import de.tum.cit.aet.hephaestus.core.WebClientConnectors;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

/**
 * WebClient configuration for the LLM proxy. Separate from the mentor WebClient — different timeout
 * profile (LLM responses can take minutes), no base URL (varies per provider per request), and an
 * independent connection pool.
 *
 * <p>Connect-time SSRF guard: {@code EgressPolicy} validates the upstream host before the call, so
 * without a guarded resolver here a DNS-rebind target (public answer during validation, private answer
 * at connect time) would sail straight through. The {@code allow-loopback} exemption is read from
 * {@link LlmProperties} so the validator and this dialler cannot drift on what it means.
 */
@Configuration
class LlmProxyWebClientConfig {

    @Bean(destroyMethod = "dispose")
    ConnectionProvider llmProxyConnectionProvider() {
        return ConnectionProvider.builder("llm-proxy-pool")
                .maxConnections(100)
                .pendingAcquireMaxCount(200)
                .maxIdleTime(Duration.ofSeconds(20))
                .maxLifeTime(Duration.ofMinutes(3))
                .pendingAcquireTimeout(Duration.ofSeconds(60))
                .evictInBackground(Duration.ofSeconds(30))
                .build();
    }

    @Bean(destroyMethod = "dispose")
    LoopResources llmProxyLoopResources() {
        // Dedicated event loop so LLM SSE streams don't compete with GitHub/GitLab sync.
        return LoopResources.create("llm-proxy", 2, true);
    }

    @Bean
    WebClient llmProxyWebClient(
            ConnectionProvider llmProxyConnectionProvider,
            LoopResources llmProxyLoopResources,
            LlmProperties llmProperties,
            WebClient.Builder webClientBuilder) {
        boolean allowLoopback = llmProperties.egress().allowLoopback();
        HttpClient httpClient = HttpClient.create(llmProxyConnectionProvider)
                .runOn(llmProxyLoopResources)
                .responseTimeout(Duration.ofMinutes(5))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .resolver(WebClientConnectors.resolverGroup(allowLoopback))
                .doOnConnected(conn ->
                        // No ReadTimeoutHandler — LLM SSE streams go silent during model thinking, so stream
                        // duration is bounded by ProxyStreamingUtils' own SSE timeout instead.
                        conn.addHandlerLast(new WriteTimeoutHandler(60)));

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(1024 * 1024))
                .build();

        return webClientBuilder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }
}
