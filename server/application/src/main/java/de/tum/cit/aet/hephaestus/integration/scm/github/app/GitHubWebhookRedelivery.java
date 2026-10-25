package de.tum.cit.aet.hephaestus.integration.scm.github.app;

import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.GITHUB_API_BASE_URL;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.egress.EgressExempt;
import de.tum.cit.aet.hephaestus.integration.core.egress.EgressExemption;
import de.tum.cit.aet.hephaestus.integration.scm.github.metrics.GitHubMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Asks GitHub to send again the App's webhook deliveries that the receiver did not accept. GitHub never retries a
 * delivery on its own, and it counts one as failed when the receiver answers an error or takes longer than 10
 * seconds, so a short broker stall would otherwise lose every event it overlaps. A redelivery keeps its
 * {@code X-GitHub-Delivery}, which the stream deduplicates.
 *
 * <p>This follows GitHub's own procedure: group the recent deliveries by GUID, and redeliver one delivery of each
 * GUID that has no successful delivery. Only a GUID whose original delivery is younger than {@link #WINDOW} is
 * considered, so its attempts all stay in view and {@link #MAX_ATTEMPTS} bounds them. A GUID whose latest answer was
 * a client error is left alone, because the receiver refused it on purpose. The unrecovered gauge counts the GUIDs
 * that failed every attempt; the reconciliation sync is what remains for them.
 *
 * @see <a href="https://docs.github.com/en/webhooks/using-webhooks/automatically-redelivering-failed-deliveries-for-a-github-app-webhook">Automatically redelivering failed deliveries for a GitHub App webhook</a>
 * @see <a href="https://docs.github.com/en/rest/apps/webhooks">REST API endpoints for GitHub App webhooks</a>
 */
@Component
@ConditionalOnServerRole
@EgressExempt(EgressExemption.WEBHOOK_REDELIVERY)
@WorkspaceAgnostic("Webhook deliveries belong to the instance's GitHub App, not to a workspace")
public class GitHubWebhookRedelivery {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookRedelivery.class);

    /** How old an event's original delivery may be. Six runs see each event. */
    static final Duration WINDOW = Duration.ofHours(1);

    /** The original delivery and three redeliveries. */
    static final int MAX_ATTEMPTS = 4;

    /** At GitHub's largest page, a run reads at most 5,000 deliveries. */
    static final int MAX_PAGES = 50;

    private static final int PAGE_SIZE = 100;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");
    private static final ParameterizedTypeReference<List<Delivery>> DELIVERIES = new ParameterizedTypeReference<>() {};

    private final GitHubAppTokenService tokens;
    private final RestClient api;
    private final Clock clock;
    private final Counter requested;
    private final Counter refused;
    private final Counter unavailable;
    private final AtomicInteger unrecovered = new AtomicInteger();

    public GitHubWebhookRedelivery(
            GitHubAppTokenService tokens,
            Clock clock,
            MeterRegistry meterRegistry,
            RestClient.Builder restClients,
            @Value("${hephaestus.integration.github.app.api-base-url:" + GITHUB_API_BASE_URL + "}") String apiBaseUrl) {
        this.tokens = tokens;
        this.clock = clock;
        // Its own timeouts: a slow GitHub must not hold the scheduler thread.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT);
        factory.setReadTimeout(TIMEOUT);
        this.api = restClients
                .requestFactory(factory)
                .baseUrl(apiBaseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
        this.requested = outcome(meterRegistry, "requested");
        this.refused = outcome(meterRegistry, "refused");
        this.unavailable = outcome(meterRegistry, "unavailable");
        Gauge.builder(GitHubMetrics.GITHUB_WEBHOOK_UNRECOVERED, unrecovered, AtomicInteger::get)
                .description("GitHub webhook events of the last hour that failed every delivery attempt")
                .register(meterRegistry);
    }

    private static Counter outcome(MeterRegistry meterRegistry, String outcome) {
        return Counter.builder(GitHubMetrics.GITHUB_WEBHOOK_REDELIVERY)
                .description("Requests to redeliver failed GitHub webhook deliveries, by outcome")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT2M")
    @SchedulerLock(name = "github-webhook-redelivery", lockAtMostFor = "PT9M", lockAtLeastFor = "PT1M")
    public void redeliverFailed() {
        if (!tokens.isConfigured()) {
            return;
        }
        Map<String, List<Delivery>> recent;
        try {
            recent = recentByGuid();
        } catch (RestClientException e) {
            unavailable.increment();
            log.warn(
                    "Could not read the GitHub App's webhook deliveries: {}",
                    e.getClass().getSimpleName());
            return;
        }
        int lost = 0;
        int redelivered = 0;
        for (List<Delivery> attempts : recent.values()) {
            if (attempts.stream().noneMatch(Delivery::original)) continue;
            if (attempts.stream().anyMatch(Delivery::succeeded)) continue;
            Delivery latest = attempts.getFirst();
            if (latest.refusedByReceiver()) continue;
            if (attempts.size() >= MAX_ATTEMPTS) {
                lost++;
            } else if (redeliver(latest)) {
                redelivered++;
            }
        }
        unrecovered.set(lost);
        if (redelivered > 0 || lost > 0) {
            log.info(
                    "Requested {} webhook redeliveries from GitHub; {} events failed every attempt", redelivered, lost);
        }
    }

    /** The deliveries of the last {@link #WINDOW}, newest first within each GUID. */
    private Map<String, List<Delivery>> recentByGuid() {
        Instant since = clock.instant().minus(WINDOW);
        Map<String, List<Delivery>> byGuid = new LinkedHashMap<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            String from = cursor;
            ResponseEntity<List<Delivery>> response = api.get()
                    .uri(builder -> {
                        builder.path("/app/hook/deliveries").queryParam("per_page", PAGE_SIZE);
                        return (from == null ? builder : builder.queryParam("cursor", from)).build();
                    })
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .retrieve()
                    .toEntity(DELIVERIES);
            List<Delivery> deliveries = response.getBody() == null ? List.of() : response.getBody();
            for (Delivery delivery : deliveries) {
                if (delivery.deliveredAt().isBefore(since)) return byGuid;
                byGuid.computeIfAbsent(delivery.guid(), guid -> new ArrayList<>())
                        .add(delivery);
            }
            cursor = nextCursor(response.getHeaders().getFirst(HttpHeaders.LINK));
            if (cursor == null) return byGuid;
        }
        log.warn(
                "Read only the first {} pages of GitHub App webhook deliveries; older ones are not redelivered",
                MAX_PAGES);
        return byGuid;
    }

    private boolean redeliver(Delivery delivery) {
        try {
            api.post()
                    .uri("/app/hook/deliveries/{id}/attempts", delivery.id())
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .retrieve()
                    .toBodilessEntity();
            requested.increment();
            return true;
        } catch (RestClientException e) {
            refused.increment();
            log.warn(
                    "GitHub refused to redeliver webhook delivery {} ({}): {}",
                    delivery.id(),
                    delivery.event(),
                    e.getClass().getSimpleName());
            return false;
        }
    }

    /** A JWT for each request: one lives 9 minutes, and a run can be longer. */
    private String bearer() {
        return "Bearer " + tokens.generateAppJWT();
    }

    private static @Nullable String nextCursor(@Nullable String link) {
        if (link == null) return null;
        Matcher next = NEXT_LINK.matcher(link);
        if (!next.find()) return null;
        return UriComponentsBuilder.fromUriString(next.group(1))
                .build()
                .getQueryParams()
                .getFirst("cursor");
    }

    /** One delivery attempt as GitHub lists it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Delivery(
            @JsonProperty("id") long id,
            @JsonProperty("guid") String guid,
            @JsonProperty("delivered_at") Instant deliveredAt,
            @JsonProperty("redelivery") boolean redelivery,
            @JsonProperty("status_code") int statusCode,
            @JsonProperty("event") @Nullable String event) {

        boolean original() {
            return !redelivery;
        }

        boolean succeeded() {
            return statusCode >= 200 && statusCode < 400;
        }

        /**
         * A client error is the receiver's own answer to this delivery; anything else may pass on another try. A
         * timeout (408) or a rate limit (429) from a proxy in front of the receiver is transient too.
         */
        boolean refusedByReceiver() {
            return statusCode >= 400 && statusCode < 500 && statusCode != 408 && statusCode != 429;
        }
    }
}
