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
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
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
 * considered, so its attempts all stay in view and {@link #MAX_ATTEMPTS} bounds them. Each redelivery waits for its
 * time in {@link #REDELIVERY_DUE}, counted from the original delivery: the gaps double, so the attempts of one event
 * outlast a long broker stall instead of being spent on the next few runs while it lasts. A GUID whose latest answer
 * was a client error is left alone, because the receiver refused it on purpose. The unrecovered gauge counts the
 * GUIDs that failed every attempt; the reconciliation sync is what remains for them.
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

    /** The fixed delay between runs of {@link #redeliverFailed()}. */
    static final String INTERVAL_ISO = "PT10M";

    static final Duration INTERVAL = Duration.parse(INTERVAL_ISO);

    /**
     * When each redelivery is due, counted from the original delivery. A run requests one once it is due, so a stall
     * shorter than the last entry is outlasted at every phase of the runs.
     */
    static final List<Duration> REDELIVERY_DUE = List.of(
            Duration.ZERO,
            Duration.ofMinutes(10),
            Duration.ofMinutes(20),
            Duration.ofMinutes(40),
            Duration.ofMinutes(80));

    /** The original delivery and one redelivery for each due time. */
    static final int MAX_ATTEMPTS = REDELIVERY_DUE.size() + 1;

    /** How old an event's original delivery may be. It covers the last due time and the run that requests it. */
    static final Duration WINDOW = Duration.ofHours(2);

    /** At GitHub's largest page, a run reads at most 10,000 deliveries. */
    static final int MAX_PAGES = 100;

    /**
     * How long one run may send requests. It starts no request after it, so a run ends within this plus one
     * request's timeouts, inside the lock it holds. A second run that starts while the first still requests would
     * spend GitHub's attempts twice. Redeliveries that do not fit wait for the next run, the oldest events first.
     */
    static final Duration RUN_BUDGET = Duration.ofMinutes(5);

    private static final int PAGE_SIZE = 100;
    /** The least time between two redelivery requests: a backlog reaches the receiver as a stream, not a burst. */
    static final Duration PACE = Duration.ofMillis(200);

    /** A run stops after this many requests in a row that GitHub refused or could not answer; the rest wait. */
    static final int REFUSALS_IN_A_ROW = 5;

    /**
     * When this many of the newest redeliveries in view failed, and none after them succeeded, the receiver is not
     * taking deliveries. A run then sends only {@link #PROBES} redeliveries instead of spending every due event's
     * attempts against it. A probe that succeeds ends this on the next run.
     */
    static final int FAILURES_IN_A_ROW = 10;

    static final int PROBES = 3;

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");
    private static final ParameterizedTypeReference<List<Delivery>> DELIVERIES = new ParameterizedTypeReference<>() {};

    private final GitHubAppTokenService tokens;
    private final RestClient api;
    private final Clock clock;
    private final Counter requested;
    private final Counter refused;
    private final Counter unavailable;
    private final Counter paused;
    private final Counter truncated;
    private final AtomicInteger unrecovered = new AtomicInteger();
    private final Sleeper sleeper;

    /** Waits between requests; a test replaces it. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    @Autowired
    public GitHubWebhookRedelivery(
            GitHubAppTokenService tokens,
            Clock clock,
            MeterRegistry meterRegistry,
            RestClient.Builder restClients,
            @Value("${hephaestus.integration.github.app.api-base-url:" + GITHUB_API_BASE_URL + "}") String apiBaseUrl) {
        this(tokens, clock, meterRegistry, restClients, apiBaseUrl, duration -> Thread.sleep(duration));
    }

    GitHubWebhookRedelivery(
            GitHubAppTokenService tokens,
            Clock clock,
            MeterRegistry meterRegistry,
            RestClient.Builder restClients,
            String apiBaseUrl,
            Sleeper sleeper) {
        this.tokens = tokens;
        this.clock = clock;
        this.sleeper = sleeper;
        // Its own timeouts: a slow GitHub must not hold the scheduler thread.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        this.api = restClients
                .requestFactory(factory)
                .baseUrl(apiBaseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
        this.requested = outcome(meterRegistry, "requested");
        this.refused = outcome(meterRegistry, "refused");
        this.unavailable = outcome(meterRegistry, "unavailable");
        this.paused = outcome(meterRegistry, "paused");
        this.truncated = outcome(meterRegistry, "truncated");
        Gauge.builder(GitHubMetrics.GITHUB_WEBHOOK_UNRECOVERED, unrecovered, AtomicInteger::get)
                .description("GitHub webhook events of the last two hours that failed every delivery attempt")
                .register(meterRegistry);
    }

    private static Counter outcome(MeterRegistry meterRegistry, String outcome) {
        return Counter.builder(GitHubMetrics.GITHUB_WEBHOOK_REDELIVERY)
                .description("Requests to redeliver failed GitHub webhook deliveries, by outcome")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = INTERVAL_ISO, initialDelayString = "PT2M")
    @SchedulerLock(name = "github-webhook-redelivery", lockAtMostFor = "PT9M", lockAtLeastFor = "PT1M")
    public void redeliverFailed() {
        if (!tokens.isConfigured()) {
            return;
        }
        Instant deadline = clock.instant().plus(RUN_BUDGET);
        Map<String, List<Delivery>> recent;
        try {
            recent = recentByGuid(deadline);
        } catch (RestClientException e) {
            unavailable.increment();
            log.warn(
                    "Could not read the GitHub App's webhook deliveries: {}",
                    e.getClass().getSimpleName());
            return;
        }
        int lost = 0;
        Instant now = clock.instant();
        List<List<Delivery>> due = new ArrayList<>();
        for (List<Delivery> attempts : recent.values()) {
            switch (next(attempts, now)) {
                case EXHAUSTED -> lost++;
                case REDELIVER -> due.add(attempts);
                case OUT_OF_VIEW, DELIVERED, REFUSED, WAIT -> {}
            }
        }
        // The oldest events leave the window first.
        due.sort(Comparator.comparing(
                attempts -> Objects.requireNonNull(original(attempts)).deliveredAt()));
        int limit = due.size();
        if (failingInARow(recent.values()) && due.size() > PROBES) {
            limit = PROBES;
            paused.increment();
            log.warn(
                    "The receiver failed the last {} redeliveries; this run sends {} of {} due redeliveries to probe it",
                    FAILURES_IN_A_ROW,
                    PROBES,
                    due.size());
        }
        int redelivered = 0;
        int sent = 0;
        int refusedInARow = 0;
        while (sent < limit && clock.instant().isBefore(deadline)) {
            if (sent > 0 && !paced()) break;
            Sent outcome = redeliver(due.get(sent++).getFirst());
            if (outcome == Sent.RATE_LIMITED) break;
            if (outcome == Sent.REQUESTED) {
                redelivered++;
                refusedInARow = 0;
            } else if (++refusedInARow >= REFUSALS_IN_A_ROW) {
                log.warn(
                        "GitHub refused {} redelivery requests in a row; the rest wait for the next run",
                        refusedInARow);
                break;
            }
        }
        // A refused or rate-limited event keeps its attempts and is due again in the next run.
        int deferred = due.size() - redelivered;
        unrecovered.set(lost);
        if (redelivered > 0 || lost > 0 || deferred > 0) {
            log.info(
                    "Requested {} webhook redeliveries from GitHub; {} events failed every attempt; {} due redeliveries"
                            + " wait for the next run",
                    redelivered,
                    lost,
                    deferred);
        }
    }

    /** What one event needs next. */
    enum Next {
        /** Its original delivery is older than the window, so its attempts cannot all be counted. */
        OUT_OF_VIEW,
        DELIVERED,
        /** The receiver refused it on purpose. */
        REFUSED,
        EXHAUSTED,
        /** Its next redelivery is not due yet. */
        WAIT,
        REDELIVER
    }

    /** @param attempts one event's attempts in view, newest first */
    static Next next(List<Delivery> attempts, Instant now) {
        Delivery original = original(attempts);
        if (original == null) return Next.OUT_OF_VIEW;
        if (attempts.stream().anyMatch(Delivery::succeeded)) return Next.DELIVERED;
        if (attempts.getFirst().refusedByReceiver()) return Next.REFUSED;
        if (attempts.size() >= MAX_ATTEMPTS) return Next.EXHAUSTED;
        Duration due = REDELIVERY_DUE.get(attempts.size() - 1);
        return original.deliveredAt().plus(due).isAfter(now) ? Next.WAIT : Next.REDELIVER;
    }

    private static @Nullable Delivery original(List<Delivery> attempts) {
        return attempts.stream().filter(Delivery::original).findFirst().orElse(null);
    }

    /**
     * The deliveries of the last {@link #WINDOW}, newest first within each GUID. It reads no page after
     * {@code deadline}; an event whose original is on an unread page is then out of view, not miscounted.
     */
    private Map<String, List<Delivery>> recentByGuid(Instant deadline) {
        Instant since = clock.instant().minus(WINDOW);
        Map<String, List<Delivery>> byGuid = new LinkedHashMap<>();
        String cursor = null;
        int page = 0;
        for (; page < MAX_PAGES && clock.instant().isBefore(deadline); page++) {
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
        truncated.increment();
        log.warn(
                "Read only the first {} pages of GitHub App webhook deliveries before the run's page or time bound;"
                        + " older ones are not redelivered in this run",
                page);
        return byGuid;
    }

    /**
     * Whether the newest {@link #FAILURES_IN_A_ROW} redeliveries in view all failed. A client error is the receiver's
     * own refusal of one delivery, so it says nothing about whether the receiver takes deliveries.
     */
    static boolean failingInARow(Collection<List<Delivery>> recent) {
        List<Delivery> newest = recent.stream()
                .flatMap(List::stream)
                .filter(delivery -> delivery.redelivery() && !delivery.refusedByReceiver())
                .sorted(Comparator.comparing(Delivery::deliveredAt).reversed())
                .limit(FAILURES_IN_A_ROW)
                .toList();
        return newest.size() == FAILURES_IN_A_ROW && newest.stream().noneMatch(Delivery::succeeded);
    }

    /** @return false when the run was interrupted, which ends it */
    private boolean paced() {
        try {
            sleeper.sleep(PACE);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private enum Sent {
        REQUESTED,
        REFUSED,
        /** GitHub asks to slow down; the rest wait for the next run. */
        RATE_LIMITED
    }

    private Sent redeliver(Delivery delivery) {
        try {
            api.post()
                    .uri("/app/hook/deliveries/{id}/attempts", delivery.id())
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .retrieve()
                    .toBodilessEntity();
            requested.increment();
            return Sent.REQUESTED;
        } catch (HttpClientErrorException e) {
            refused.increment();
            int status = e.getStatusCode().value();
            if (status == 403 || status == 429) {
                log.warn("GitHub limited the rate of redelivery requests ({}); the rest wait for the next run", status);
                return Sent.RATE_LIMITED;
            }
            log.warn(
                    "GitHub refused to redeliver webhook delivery {} ({}): {}",
                    delivery.id(),
                    delivery.event(),
                    status);
            return Sent.REFUSED;
        } catch (RestClientException e) {
            refused.increment();
            log.warn(
                    "GitHub refused to redeliver webhook delivery {} ({}): {}",
                    delivery.id(),
                    delivery.event(),
                    e.getClass().getSimpleName());
            return Sent.REFUSED;
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

        /** GitHub counts only a 2xx answer as delivered; a redirect is a failed delivery. */
        boolean succeeded() {
            return statusCode / 100 == 2;
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
