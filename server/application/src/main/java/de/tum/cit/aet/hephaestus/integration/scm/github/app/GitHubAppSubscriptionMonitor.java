package de.tum.cit.aet.hephaestus.integration.scm.github.app;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.GITHUB_API_BASE_URL;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.egress.EgressExempt;
import de.tum.cit.aet.hephaestus.integration.core.egress.EgressExemption;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.metrics.GitHubMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** Compares the instance App's event subscriptions with the registration manifest. */
@Component
@ConditionalOnServerRole
@EgressExempt(EgressExemption.OPERATOR_ALERT)
class GitHubAppSubscriptionMonitor {

    private static final Logger log = LoggerFactory.getLogger(GitHubAppSubscriptionMonitor.class);

    // GitHub sends installation events without opt-in. The manifest contract test checks the rest of the enum.
    private static final Set<String> IMPLICIT_EVENTS = Set.of(
            GitHubEventType.INSTALLATION.getValue(),
            GitHubEventType.INSTALLATION_REPOSITORIES.getValue(),
            GitHubEventType.INSTALLATION_TARGET.getValue());
    private static final Set<String> REQUIRED_EVENTS = Arrays.stream(GitHubEventType.values())
            .map(GitHubEventType::getValue)
            .filter(event -> !IMPLICIT_EVENTS.contains(event))
            .collect(Collectors.toUnmodifiableSet());

    private final GitHubAppTokenService tokens;
    private final RestClient api;
    private final AtomicReference<@Nullable Drift> latest = new AtomicReference<>();

    GitHubAppSubscriptionMonitor(
            GitHubAppTokenService tokens,
            MeterRegistry meters,
            RestClient.Builder builder,
            @Value("${hephaestus.integration.github.app.api-base-url:" + GITHUB_API_BASE_URL + "}") String apiBaseUrl) {
        this.tokens = tokens;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.api = builder.requestFactory(factory)
                .baseUrl(apiBaseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
        Gauge.builder(GitHubMetrics.GITHUB_APP_SUBSCRIPTION_DRIFT, latest, GitHubAppSubscriptionMonitor::extra)
                .description("Number of GitHub App webhook subscriptions that differ from the manifest")
                .tag("change", "extra")
                .register(meters);
        Gauge.builder(GitHubMetrics.GITHUB_APP_SUBSCRIPTION_DRIFT, latest, GitHubAppSubscriptionMonitor::missing)
                .description("Number of GitHub App webhook subscriptions that differ from the manifest")
                .tag("change", "missing")
                .register(meters);
    }

    // Each server replica reports its own reading, so none can inherit a stale result from a previous run.
    @Scheduled(fixedDelayString = "P1D")
    @WorkspaceAgnostic("Subscription settings belong to the instance GitHub App")
    public void check() {
        if (!tokens.isConfigured()) return;
        try {
            Drift drift = compare(readSubscriptions());
            latest.set(drift);
            if (!drift.extra().isEmpty() || !drift.missing().isEmpty()) {
                log.warn(
                        "GitHub App webhook subscriptions differ from the manifest: extra={}, missing={}",
                        sanitizeForLog(new TreeSet<>(drift.extra()).toString()),
                        new TreeSet<>(drift.missing()));
            }
        } catch (RestClientException | IllegalStateException e) {
            log.warn(
                    "Could not check the GitHub App's webhook subscriptions: {}",
                    e.getClass().getSimpleName());
        }
    }

    private Set<String> readSubscriptions() {
        JsonNode response = api.get()
                .uri("/app")
                .headers(headers -> headers.setBearerAuth(tokens.generateAppJWT()))
                .retrieve()
                .body(JsonNode.class);
        if (response == null || !response.path("events").isArray()) {
            throw new IllegalStateException("The GitHub App response must contain an events array");
        }
        Set<String> subscribed = new HashSet<>();
        for (JsonNode event : response.path("events")) {
            if (!event.isString() || event.asString().isBlank()) {
                throw new IllegalStateException("The GitHub App events array must contain event names");
            }
            subscribed.add(event.asString());
        }
        return subscribed;
    }

    private static Drift compare(Set<String> subscribed) {
        var extra = new HashSet<>(subscribed);
        extra.removeAll(REQUIRED_EVENTS);
        extra.removeAll(IMPLICIT_EVENTS);
        var missing = new HashSet<>(REQUIRED_EVENTS);
        missing.removeAll(subscribed);
        return new Drift(Set.copyOf(extra), Set.copyOf(missing));
    }

    private static double extra(AtomicReference<@Nullable Drift> latest) {
        Drift drift = latest.get();
        return drift == null ? Double.NaN : drift.extra().size();
    }

    private static double missing(AtomicReference<@Nullable Drift> latest) {
        Drift drift = latest.get();
        return drift == null ? Double.NaN : drift.missing().size();
    }

    private record Drift(Set<String> extra, Set<String> missing) {}
}
