package de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql;

import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.HEADER_RATE_LIMIT_LIMIT;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.HEADER_RATE_LIMIT_OBSERVED;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.HEADER_RATE_LIMIT_REMAINING;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.HEADER_RATE_LIMIT_RESET;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.security.ScmEgressProperties;
import de.tum.cit.aet.hephaestus.core.security.ScmServerEndpointPolicy;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderReply;
import de.tum.cit.aet.hephaestus.integration.core.spi.RateLimitSnapshot;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabRateLimitTracker;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for the rate-limit tracking exchange filter in {@link GitLabGraphQlConfig}.
 *
 * <p>The filter must read the per-request {@link GitLabGraphQlClientProvider#SCOPE_ID_ATTRIBUTE} and
 * feed the response headers to {@link GitLabRateLimitTracker#updateFromHeaders}. If this wiring
 * regresses (e.g. the attribute is dropped or the scopeId no longer propagates), the admin sync UI
 * silently loses GitLab rate-limit data on every instance that advertises rate limits for
 * authenticated traffic.
 */
@Tag("unit")
class GitLabGraphQlConfigRateLimitFilterTest extends BaseUnitTest {

    private static final URI GRAPHQL_URI = URI.create("https://gitlab.example.com/api/graphql");

    private GitLabRateLimitTracker tracker;
    private GitLabGraphQlConfig config;
    private ExchangeFilterFunction filter;

    @BeforeEach
    void setUp() {
        tracker = new GitLabRateLimitTracker(new SimpleMeterRegistry());
        config = new GitLabGraphQlConfig(
                tracker,
                new ScmServerEndpointPolicy(
                        new MockEnvironment(), WebClient.builder(), new ScmEgressProperties(List.of())));
        filter = config.rateLimitTrackingFilter();
    }

    @Test
    void shouldRecordRateLimitForScopeWhenResponseCarriesHeaders() {
        Instant resetAt = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
        ClientRequest request = requestForScope(7L);
        ClientResponse response = responseWithRateLimitHeaders(80, 100, resetAt, 5);

        filter.filter(request, req -> Mono.just(response)).block();

        RateLimitSnapshot snapshot = tracker.snapshot(7L);
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.remaining()).isEqualTo(80);
        assertThat(snapshot.limit()).isEqualTo(100);
        assertThat(snapshot.resetAt()).isEqualTo(resetAt);
    }

    @Test
    void shouldNotRecordWhenScopeIdAttributeMissing() {
        ClientRequest request =
                ClientRequest.create(HttpMethod.POST, GRAPHQL_URI).build();
        ClientResponse response =
                responseWithRateLimitHeaders(80, 100, Instant.now().plusSeconds(60), 5);

        filter.filter(request, req -> Mono.just(response)).block();

        // No scopeId attribute → nothing to key state on → snapshot stays null (honest "never observed").
        assertThat(tracker.getTrackedScopeCount()).isZero();
        assertThat(tracker.snapshot(7L)).isNull();
    }

    @Test
    void shouldNotRecordWhenResponseOmitsRateLimitHeaders() {
        // Reproduces the gitlab.lrz.de reality: authenticated API responses carry no RateLimit-*
        // headers, so there is nothing to capture and the snapshot must remain null.
        ClientRequest request = requestForScope(7L);
        ClientResponse response = ClientResponse.create(HttpStatus.OK, ExchangeStrategies.withDefaults())
                .build();

        filter.filter(request, req -> Mono.just(response)).block();

        assertThat(tracker.snapshot(7L)).isNull();
    }

    private ClientRequest requestForScope(long scopeId) {
        return ClientRequest.create(HttpMethod.POST, GRAPHQL_URI)
                .attribute(GitLabGraphQlClientProvider.SCOPE_ID_ATTRIBUTE, scopeId)
                .build();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldPassTheThrottleDeadlineToCommitCorrectionAndRetryForOtherCallers(boolean commitCorrection) {
        var builder = ClientRequest.create(HttpMethod.POST, GRAPHQL_URI);
        if (commitCorrection) {
            builder.attribute(GitLabGraphQlClientProvider.EXCHANGE_ATTRIBUTE, new ProviderReply.Exchange());
        }
        ClientRequest request = builder.build();
        var attempts = new AtomicInteger();

        StepVerifier.withVirtualTime(() -> config.retryFilter()
                        .filter(
                                request,
                                ignored -> Mono.fromSupplier(() -> attempts.incrementAndGet() == 1
                                        ? ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                                                .header(HttpHeaders.RETRY_AFTER, "60")
                                                .build()
                                        : ClientResponse.create(HttpStatus.OK).build())))
                .thenAwait(Duration.ofSeconds(10))
                .assertNext(response -> {
                    assertThat(response.statusCode())
                            .isEqualTo(commitCorrection ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.OK);
                    if (commitCorrection)
                        assertThat(response.headers().header(HttpHeaders.RETRY_AFTER))
                                .containsExactly("60");
                })
                .verifyComplete();
        assertThat(attempts).hasValue(commitCorrection ? 1 : 2);
    }

    private ClientResponse responseWithRateLimitHeaders(int remaining, int limit, Instant resetAt, int observed) {
        return ClientResponse.create(HttpStatus.OK, ExchangeStrategies.withDefaults())
                .header(HEADER_RATE_LIMIT_REMAINING, String.valueOf(remaining))
                .header(HEADER_RATE_LIMIT_LIMIT, String.valueOf(limit))
                .header(HEADER_RATE_LIMIT_RESET, String.valueOf(resetAt.getEpochSecond()))
                .header(HEADER_RATE_LIMIT_OBSERVED, String.valueOf(observed))
                .build();
    }
}
