package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.EgressPolicy;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
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
 * at connect time) would sail straight through. The resolver comes from {@code EgressPolicy} itself, so
 * the validator and this dialler cannot drift on what the {@code allow-loopback} and {@code private-hosts}
 * exemptions mean.
 */
@Configuration
@ConditionalOnWorkerRole
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

    /**
     * How long a response may send nothing: the review runner's {@code STALL_MS} in {@code pi-runner.ts}, after which
     * it gives up on a silent session. A silent stream then frees its place in the attempt's cap at the same time.
     * {@code ReviewRunnerConstantsTest} pins the two.
     */
    static final Duration IDLE = Duration.ofMinutes(5);

    @Bean
    WebClient llmProxyWebClient(
            ConnectionProvider llmProxyConnectionProvider,
            LoopResources llmProxyLoopResources,
            EgressPolicy egressPolicy,
            WebClient.Builder webClientBuilder) {
        // Two independent limits bound a response. responseTimeout is an idle limit: Reactor Netty fails the response
        // when no bytes arrive for 5 minutes, however long the response has run. The total is the caller's: the
        // proxy ends each exchange at the attempt's work deadline, or, for a mentor turn, at its own fixed limits.
        HttpClient httpClient = HttpClient.create(llmProxyConnectionProvider)
                .runOn(llmProxyLoopResources)
                .responseTimeout(IDLE)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .resolver(egressPolicy.resolverGroup())
                // Only the configured base URL is egress-validated: a 3xx to anywhere else is not followed.
                .followRedirect(false)
                .doOnConnected(conn -> conn.addHandlerLast(new WriteTimeoutHandler(60)));

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(1024 * 1024))
                .build();

        return webClientBuilder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }
}
