package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.GITHUB_API_BASE_URL;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import io.netty.resolver.DefaultAddressResolverGroup;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Learns a scope's <em>real</em> GitHub GraphQL ceiling from REST {@code GET /rate_limit} before the first
 * GraphQL call of a process, so the admin surface never has to fall back on an assumption.
 *
 * <h2>Why this exists</h2>
 * GitHub's GraphQL budget is not a universal 5,000 points/hour. A GitHub App installation with more than
 * 20 repositories earns another 50 points/hour per repository, capped at 12,500; Enterprise Cloud
 * installations get 10,000; an Actions {@code GITHUB_TOKEN} gets 1,000 per repository. A flat 5,000
 * assumption is therefore wrong for precisely the large installations where the number matters most.
 *
 * <h2>Why it is free</h2>
 * GitHub documents {@code GET /rate_limit} as not counting against the rate limit — "Accessing this
 * endpoint does not count against your REST API rate limit"
 * (<a href="https://docs.github.com/en/rest/rate-limit/rate-limit">REST: Get rate limit status</a>). Its
 * {@code resources.graphql} entry carries exactly the {@code limit}/{@code remaining}/{@code reset} triple
 * the GraphQL {@code rateLimit} field reports, so what we feed the tracker is a genuine measurement, not a
 * seed. (The GraphQL alternative — a {@code rateLimit(dryRun: true)}-only query — would work too, but only
 * the REST endpoint is explicitly documented as quota-free.)
 *
 * <h2>Cost control</h2>
 * The probe fires at most once per scope while that scope has no observation, and is re-attempted no more
 * often than {@link #RETRY_BACKOFF} after a failure. It is issued fire-and-forget: it never blocks, delays,
 * or fails a sync — a scope whose probe fails simply keeps reporting nothing until the first GraphQL
 * response arrives, which is the honest outcome.
 */
@Component
@Slf4j
@WorkspaceAgnostic("System-wide rate-limit seeding — probes GitHub's REST rate-limit endpoint per scope")
public class GitHubRestRateLimitSeeder {

    /** Minimum spacing between probe attempts for a scope that has still not been observed. */
    private static final Duration RETRY_BACKOFF = Duration.ofMinutes(10);

    /** The probe must never hold anything up; a slow GitHub just means we stay "not reported" for now. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    private final RateLimitObservationSink rateLimitTracker;
    private final WebClient webClient;
    private final ConcurrentHashMap<Long, Instant> lastAttemptByScope = new ConcurrentHashMap<>();

    public GitHubRestRateLimitSeeder(RateLimitObservationSink rateLimitTracker, WebClient.Builder webClients) {
        this.rateLimitTracker = rateLimitTracker;
        HttpClient httpClient = HttpClient.create().resolver(DefaultAddressResolverGroup.INSTANCE);
        this.webClient = webClients
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(GITHUB_API_BASE_URL)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /**
     * Probes {@code GET /rate_limit} for a scope that has nothing observed yet. Returns immediately; the
     * observation lands asynchronously.
     */
    public void seedIfUnobserved(@Nullable Long scopeId, @Nullable String token) {
        if (scopeId == null || token == null || token.isBlank()) {
            return;
        }
        if (rateLimitTracker.hasObservation(scopeId)) {
            return;
        }
        if (!claimAttempt(scopeId)) {
            return;
        }
        webClient
                .get()
                .uri("/rate_limit")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .bodyToMono(RateLimitStatusResponse.class)
                .timeout(PROBE_TIMEOUT)
                .subscribe(
                        response -> apply(scopeId, response),
                        error -> log.debug(
                                "GitHub REST rate-limit probe failed; staying unreported for scope {}: {}",
                                scopeId,
                                error.toString()));
    }

    /** True if this call won the right to probe now — one in-flight probe per scope per backoff window. */
    private boolean claimAttempt(Long scopeId) {
        Instant now = Instant.now();
        Instant threshold = now.minus(RETRY_BACKOFF);
        Instant previous = lastAttemptByScope.get(scopeId);
        if (previous != null && previous.isAfter(threshold)) {
            return false;
        }
        return lastAttemptByScope
                .merge(scopeId, now, (existing, candidate) -> existing.isAfter(threshold) ? existing : candidate)
                .equals(now);
    }

    private void apply(Long scopeId, @Nullable RateLimitStatusResponse response) {
        Instant observedAt = Instant.now();
        RateLimitResource graphql = response == null || response.resources() == null
                ? null
                : response.resources().get("graphql");
        if (graphql == null || graphql.limit() == null || graphql.remaining() == null) {
            // Nothing measured — record nothing. GitHub Enterprise Server builds without a graphql
            // resource entry land here, and "not reported" is the correct display for them.
            log.debug("GitHub REST rate-limit probe returned no graphql resource for scope {}", scopeId);
            return;
        }
        Instant resetAt = graphql.reset() == null ? null : Instant.ofEpochSecond(graphql.reset());
        rateLimitTracker.updateFromRestRateLimit(scopeId, graphql.limit(), graphql.remaining(), resetAt, observedAt);
    }

    /** {@code GET /rate_limit} body; only the {@code graphql} entry of {@code resources} is consumed. */
    private record RateLimitStatusResponse(Map<String, RateLimitResource> resources) {}

    private record RateLimitResource(Integer limit, Integer remaining, Long reset) {}
}
