package de.tum.cit.aet.hephaestus.integration.scm.github.graphql;

import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_INITIAL_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_RETRIES;

import de.tum.cit.aet.hephaestus.integration.core.egress.SilentModeGraphQlClientFactory;
import de.tum.cit.aet.hephaestus.integration.core.graphql.FragmentMergingDocumentSource;
import de.tum.cit.aet.hephaestus.integration.scm.common.ScmTransportErrors;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHActor;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2FieldConfiguration;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemContent;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemFieldValue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2Owner;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHRepositoryOwner;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHRequestedReviewer;
import de.tum.cit.aet.hephaestus.integration.scm.github.jackson.GitHubActorMixin;
import de.tum.cit.aet.hephaestus.integration.scm.github.jackson.GitHubIssueMixin;
import de.tum.cit.aet.hephaestus.integration.scm.github.jackson.GitHubProjectV2FieldConfigurationMixin;
import de.tum.cit.aet.hephaestus.integration.scm.github.jackson.GitHubProjectV2ItemContentMixin;
import de.tum.cit.aet.hephaestus.integration.scm.github.jackson.GitHubProjectV2ItemFieldValueMixin;
import de.tum.cit.aet.hephaestus.integration.scm.github.jackson.GitHubProjectV2OwnerMixin;
import de.tum.cit.aet.hephaestus.integration.scm.github.jackson.GitHubPullRequestMixin;
import de.tum.cit.aet.hephaestus.integration.scm.github.jackson.GitHubRepositoryOwnerMixin;
import de.tum.cit.aet.hephaestus.integration.scm.github.jackson.GitHubRequestedReviewerMixin;
import de.tum.cit.aet.hephaestus.integration.scm.github.metrics.GithubMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.resolver.DefaultAddressResolverGroup;
import java.io.Serial;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.util.retry.Retry;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Configuration for the GitHub GraphQL API client: rate-limit tracking, retry with backoff, and
 * document loading from {@code classpath:graphql-documents/}.
 *
 * @see org.springframework.graphql.client.HttpGraphQlClient
 */
@Configuration
public class GitHubGraphQlConfig {

    private static final Logger log = LoggerFactory.getLogger(GitHubGraphQlConfig.class);

    private static final String GITHUB_GRAPHQL_URL = "https://api.github.com/graphql";
    private static final int MAX_BUFFER_SIZE = 16 * 1024 * 1024;

    // GitHub infrastructure errors (502/504) typically resolve within 30-60 seconds. These values
    // provide ~135 seconds of total retry time:
    // Attempt 1: ~5s, Attempt 2: ~10s, Attempt 3: ~20s, Attempt 4: ~40s, Attempt 5: ~60s (capped)
    private static final int MAX_RETRIES = 5;
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(5);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);
    private static final double JITTER_FACTOR = 0.5;

    private static final String HEADER_RATE_LIMIT = "x-ratelimit-limit";
    private static final String HEADER_RATE_REMAINING = "x-ratelimit-remaining";
    private static final String HEADER_RATE_RESET = "x-ratelimit-reset";
    private static final String HEADER_RATE_USED = "x-ratelimit-used";
    private static final String HEADER_RETRY_AFTER = "retry-after";

    private final AtomicInteger rateLimitRemaining = new AtomicInteger(0);
    private final AtomicInteger rateLimitLimit = new AtomicInteger(0);
    private final AtomicInteger rateLimitUsed = new AtomicInteger(0);

    public GitHubGraphQlConfig(MeterRegistry meterRegistry) {
        Gauge.builder(GithubMetrics.GITHUB_GRAPHQL_RATELIMIT_REMAINING, rateLimitRemaining, AtomicInteger::get)
                .description("GitHub GraphQL API rate limit points remaining")
                .register(meterRegistry);
        Gauge.builder(GithubMetrics.GITHUB_GRAPHQL_RATELIMIT_LIMIT, rateLimitLimit, AtomicInteger::get)
                .description("GitHub GraphQL API rate limit total points")
                .register(meterRegistry);
        Gauge.builder(GithubMetrics.GITHUB_GRAPHQL_RATELIMIT_USED, rateLimitUsed, AtomicInteger::get)
                .description("GitHub GraphQL API rate limit points used")
                .register(meterRegistry);
    }

    /**
     * Builds the mapper every GitHub GraphQL response is decoded with.
     *
     * <p>Exposed as a static factory rather than inlined into {@link #gitHubGraphQlWebClient} so
     * tests can decode against the real mixin set. Several mixins install
     * {@code @JsonTypeInfo(property = "__typename")} type resolvers that Jackson inherits onto the
     * concrete node types, so whether an operation document decodes at all depends on this exact
     * configuration.
     *
     * @param baseObjectMapper the application-wide mapper to derive from
     * @return the mapper used by the GitHub GraphQL codecs
     */
    public static JsonMapper gitHubGraphQlObjectMapper(JsonMapper baseObjectMapper) {
        // GitHub databaseId values exceed 32-bit int range.
        return baseObjectMapper
                .rebuild()
                .enable(DeserializationFeature.USE_LONG_FOR_INTS)
                .addMixIn(GHActor.class, GitHubActorMixin.class)
                .addMixIn(GHRequestedReviewer.class, GitHubRequestedReviewerMixin.class)
                .addMixIn(GHRepositoryOwner.class, GitHubRepositoryOwnerMixin.class)
                .addMixIn(GHProjectV2Owner.class, GitHubProjectV2OwnerMixin.class)
                .addMixIn(GHProjectV2FieldConfiguration.class, GitHubProjectV2FieldConfigurationMixin.class)
                .addMixIn(GHProjectV2ItemContent.class, GitHubProjectV2ItemContentMixin.class)
                .addMixIn(GHProjectV2ItemFieldValue.class, GitHubProjectV2ItemFieldValueMixin.class)
                .addMixIn(GHIssue.class, GitHubIssueMixin.class)
                .addMixIn(GHPullRequest.class, GitHubPullRequestMixin.class)
                .build();
    }

    @Bean
    public WebClient gitHubGraphQlWebClient(JsonMapper baseObjectMapper) {
        JsonMapper graphQlObjectMapper = gitHubGraphQlObjectMapper(baseObjectMapper);

        // The buffer limit must be set on the CUSTOM decoder, not only via
        // defaultCodecs().maxInMemorySize(): the latter governs the default codecs, but our
        // custom JacksonJsonDecoder (registered for GitHub's 64-bit databaseId handling) keeps
        // its own 256 KB default. Without this, large PR pages with embedded reviews/threads
        // fail with "DataBufferLimitException: Exceeded limit on max bytes to buffer : 262144".
        JacksonJsonDecoder graphQlJsonDecoder = new JacksonJsonDecoder(graphQlObjectMapper);
        graphQlJsonDecoder.setMaxInMemorySize(MAX_BUFFER_SIZE);

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(config -> {
                    config.defaultCodecs().maxInMemorySize(MAX_BUFFER_SIZE);
                    config.customCodecs().register(new JacksonJsonEncoder(graphQlObjectMapper));
                    config.customCodecs().register(graphQlJsonDecoder);
                })
                .build();

        // Aggressive eviction mitigates PrematureCloseException: GitHub's load balancers close
        // connections after ~60s of idle time, so recycling connections more frequently reduces
        // the chance of reusing a stale connection that gets closed mid-response.
        ConnectionProvider connectionProvider = ConnectionProvider.builder("github-graphql")
                .maxConnections(50)
                .maxIdleTime(Duration.ofSeconds(20))
                .maxLifeTime(Duration.ofMinutes(3))
                .pendingAcquireTimeout(Duration.ofSeconds(60))
                .evictInBackground(Duration.ofSeconds(60))
                .lifo() // prefer fresher connections
                .build();

        HttpClient httpClient = HttpClient.create(connectionProvider)
                .resolver(DefaultAddressResolverGroup.INSTANCE)
                .responseTimeout(
                        Duration.ofSeconds(135)); // Must exceed longest block() timeout (backfillGraphqlTimeout=120s)

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(GITHUB_GRAPHQL_URL)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .exchangeStrategies(strategies)
                .filter(rateLimitLoggingFilter())
                .filter(retryFilter())
                .filter(transportErrorRetryFilter())
                .build();
    }

    @Bean
    public HttpGraphQlClient gitHubGraphQlClient(
            WebClient gitHubGraphQlWebClient, SilentModeGraphQlClientFactory clientFactory) {
        // Operations are loaded from graphql/github/operations/ by name.
        // Shared fragments are appended selectively: only those actually referenced (transitively via
        // ...FragmentName spreads) are included, which is what satisfies GraphQL spec §5.5.1.4
        // (fragments must be used) while keeping the definitions in documents the validators can see.
        List<Resource> fragmentFiles = List.of(
                new ClassPathResource(GitHubGraphQlFragments.PROJECT_FRAGMENTS_RESOURCE),
                new ClassPathResource(GitHubGraphQlFragments.COMMIT_ENRICHMENT_FIELDS_RESOURCE));
        FragmentMergingDocumentSource documentSource = new FragmentMergingDocumentSource(
                List.of(
                        new ClassPathResource("graphql/github/operations/"),
                        new ClassPathResource("graphql/github/fragments/")),
                List.of(".graphql", ".gql"),
                fragmentFiles);
        return clientFactory.create(gitHubGraphQlWebClient, documentSource);
    }

    /**
     * Logs GitHub rate limit information from response headers.
     * <p>
     * GitHub returns these headers on every response:
     * <ul>
     * <li>{@code x-ratelimit-limit}: Total points allowed per hour</li>
     * <li>{@code x-ratelimit-remaining}: Points remaining in current window</li>
     * <li>{@code x-ratelimit-used}: Points used in current window</li>
     * <li>{@code x-ratelimit-reset}: Unix timestamp when limit resets</li>
     * </ul>
     */
    private ExchangeFilterFunction rateLimitLoggingFilter() {
        return ExchangeFilterFunction.ofResponseProcessor(response -> {
            logRateLimitInfo(response);
            return Mono.just(response);
        });
    }

    private void logRateLimitInfo(ClientResponse response) {
        HttpHeaders headers = response.headers().asHttpHeaders();

        String remaining = headers.getFirst(HEADER_RATE_REMAINING);
        String limit = headers.getFirst(HEADER_RATE_LIMIT);
        String used = headers.getFirst(HEADER_RATE_USED);
        String reset = headers.getFirst(HEADER_RATE_RESET);

        if (remaining != null && limit != null) {
            int remainingInt = Integer.parseInt(remaining);
            int limitInt = Integer.parseInt(limit);
            int usedInt = used != null ? Integer.parseInt(used) : limitInt - remainingInt;

            rateLimitRemaining.set(remainingInt);
            rateLimitLimit.set(limitInt);
            rateLimitUsed.set(usedInt);

            double usagePercent = (100.0 * (limitInt - remainingInt)) / limitInt;
            if (usagePercent > 80) {
                log.warn(
                        "Approaching GitHub GraphQL rate limit: remaining={}, limit={}, usagePercent={}, resetEpoch={}",
                        remaining,
                        limit,
                        String.format("%.1f", usagePercent),
                        reset);
            }
        }

        if (response.statusCode().value() == 429) {
            String retryAfter = headers.getFirst(HEADER_RETRY_AFTER);
            log.error("Exceeded GitHub rate limit: retryAfterSeconds={}", retryAfter);
        }
    }

    /**
     * Retries 5xx server errors and 429 (rate limited) responses with exponential backoff; see
     * {@link #MAX_RETRIES} for the timing rationale.
     */
    private ExchangeFilterFunction retryFilter() {
        return (request, next) -> next.exchange(request)
                .flatMap(response -> {
                    HttpStatusCode status = response.statusCode();

                    if (status.is4xxClientError() && status.value() != 429) {
                        return Mono.just(response);
                    }

                    if (status.is5xxServerError() || status.value() == 429) {
                        // Release the body to prevent a resource leak on the discarded response
                        return response.releaseBody().then(Mono.error(new RetryableException(status.value())));
                    }

                    return Mono.just(response);
                })
                .retryWhen(Retry.backoff(MAX_RETRIES, INITIAL_BACKOFF)
                        .maxBackoff(MAX_BACKOFF)
                        .jitter(JITTER_FACTOR)
                        .filter(throwable -> throwable instanceof RetryableException)
                        .doBeforeRetry(signal -> {
                            RetryableException ex = (RetryableException) signal.failure();
                            log.warn(
                                    "Retrying GitHub GraphQL request: statusCode={}, attempt={}, maxRetries={}",
                                    ex.getStatusCode(),
                                    signal.totalRetries() + 1,
                                    MAX_RETRIES);
                        })
                        .onRetryExhaustedThrow((spec, signal) -> {
                            RetryableException ex = (RetryableException) signal.failure();
                            log.error(
                                    "Failed GitHub GraphQL request after retries exhausted: retryCount={}, statusCode={}",
                                    MAX_RETRIES,
                                    ex.getStatusCode());
                            return new GitHubGraphQlException(
                                    "GitHub GraphQL request failed after " + MAX_RETRIES + " retries",
                                    ex.getStatusCode());
                        }));
    }

    /**
     * Retry filter for transport-level errors that occur during response streaming.
     * <p>
     * This handles errors like {@code PrematureCloseException} which occur when:
     * <ul>
     *   <li>GitHub closes the connection mid-response (large payloads, infrastructure issues)</li>
     *   <li>Network intermediaries (load balancers, proxies) timeout during streaming</li>
     *   <li>Stale keep-alive connections are reused after server-side timeout</li>
     * </ul>
     * <p>
     * These errors are distinct from HTTP status code errors (handled by {@link #retryFilter()})
     * because they occur at the transport layer, after the HTTP status has been received.
     * <p>
     * Uses shorter backoff than HTTP errors since transport issues often resolve quickly.
     */
    private ExchangeFilterFunction transportErrorRetryFilter() {
        return (request, next) -> next.exchange(request)
                .retryWhen(Retry.backoff(TRANSPORT_MAX_RETRIES, TRANSPORT_INITIAL_BACKOFF)
                        .maxBackoff(TRANSPORT_MAX_BACKOFF)
                        .jitter(JITTER_FACTOR)
                        .filter(ScmTransportErrors::isTransportError)
                        .doBeforeRetry(signal -> {
                            Throwable failure = signal.failure();
                            log.warn(
                                    "Retrying GitHub GraphQL request after transport error: errorType={}, message={}, attempt={}, maxRetries={}",
                                    failure.getClass().getSimpleName(),
                                    failure.getMessage(),
                                    signal.totalRetries() + 1,
                                    TRANSPORT_MAX_RETRIES);
                        })
                        .onRetryExhaustedThrow((spec, signal) -> {
                            Throwable failure = signal.failure();
                            log.error(
                                    "Failed GitHub GraphQL request after transport retries exhausted: retryCount={}, errorType={}, message={}",
                                    TRANSPORT_MAX_RETRIES,
                                    failure.getClass().getSimpleName(),
                                    failure.getMessage());
                            return new TransportException(
                                    "GitHub GraphQL request failed after " + TRANSPORT_MAX_RETRIES
                                            + " retries due to transport error: "
                                            + failure.getMessage(),
                                    failure);
                        }));
    }

    private static class RetryableException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final int statusCode;

        RetryableException(int statusCode) {
            super("Retryable HTTP error: " + statusCode);
            this.statusCode = statusCode;
        }

        int getStatusCode() {
            return statusCode;
        }
    }

    public static class GitHubGraphQlException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final int statusCode;

        public GitHubGraphQlException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }

    public static class TransportException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        public TransportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
