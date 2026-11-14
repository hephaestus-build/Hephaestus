package de.tum.cit.aet.hephaestus.observability;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The metric names that the release's alert rules and dashboards read from the roles ({@code job="hephaestus"})
 * exist: in the code for every role, and, for the rules, on the scrape of this context for each meter that the server
 * role registers at start. A renamed meter, or a rule or panel that reads a name that no role exports, fails here
 * instead of leaving an alert that never fires or a panel that stays empty.
 */
// An own context: the shared one also holds the meters of other classes, so its series depend on the class order.
@TestPropertySource(properties = "hephaestus.test.context=scrape-contract")
class ScrapeContractIntegrationTest extends BaseIntegrationTest {

    private static final Path RULES = Path.of("../../deploy/chart/hephaestus/files/hephaestus-alerts.yml");
    private static final Path DASHBOARDS = Path.of("../../deploy/chart/hephaestus/files/dashboards");
    private static final Path SOURCES = Path.of("src/main/java");

    /** Name prefixes of meters that a library registers, not this code: Spring MVC, HikariCP and the JVM. */
    private static final Set<String> LIBRARY = Set.of("http_server_requests", "hikaricp_connections", "jvm_");

    /**
     * Rule metrics that this context does not export at start, by the exact name that the rule selects, each with the
     * reason. A new name belongs here only when the meter cannot exist before its first event, or only another role
     * registers it.
     */
    private static final Map<String, String> ABSENT_AT_START = Map.ofEntries(
            Map.entry(
                    "http_server_requests_seconds_bucket",
                    "a request creates its route's timer; checked after one below"),
            Map.entry("http_server_requests_seconds_count", "a request creates its route's timer"),
            Map.entry("web_vital_duration_seconds_bucket", "a browser report creates it"),
            Map.entry("web_vital_duration_seconds_count", "a browser report creates it"),
            Map.entry("commit_identity_pending", "the commit link correction job registers it when enabled"),
            Map.entry("commit_identity_resolutions_total", "the commit link correction job counts each answer"),
            Map.entry("tenancy_violation_total", "a refused statement creates its series"),
            Map.entry("agent_job_infra_retry_requeued_total", "worker role"),
            Map.entry("agent_job_server_error_total", "worker role"),
            Map.entry("sandbox_gateway_connections", "worker role"),
            Map.entry("sandbox_gateway_connections_limit", "worker role"),
            Map.entry("sandbox_gateway_refused_total", "worker role"),
            Map.entry("attempt_key_sync_failures_total", "worker role, and the first failure creates it"),
            Map.entry("agent_sandbox_reaper_failure_total", "worker role"),
            Map.entry("agent_sandbox_revoked_total", "worker role"),
            Map.entry("agent_sandbox_dispatch_failure_total", "worker role"),
            Map.entry("llm_pool_limit", "worker role"),
            Map.entry("llm_pool_lease_renewal_failures_total", "worker role"),
            Map.entry("llm_pool_permit_wait_seconds_bucket", "worker role"),
            Map.entry("llm_pool_permit_wait_seconds_count", "worker role"),
            Map.entry("llm_pool_permits_expired_total", "worker role"),
            Map.entry("llm_pool_limit_decreases_total", "worker role"),
            Map.entry("llm_pool_first_token_baseline_resets_total", "worker role"),
            Map.entry("llm_pool_upstream_absorbed_total", "worker role"),
            Map.entry("llm_pool_upstream_throttled_total", "worker role"),
            Map.entry("llm_budget_blocked_total", "the first blocked call creates it, with its surface and cap"),
            Map.entry("llm_budget_exhausted_total", "the first exhausted budget creates it, with its cap"),
            Map.entry("conversation_turn_completed_total", "the first ended turn creates it, with its outcome"),
            Map.entry("event_publications_overdue", "the first overdue publication creates it, with its listener"),
            Map.entry("integration_consumer_retry_total", "the first retry creates it, with its kind"),
            Map.entry("integration_consumer_poison_total", "the first dead letter creates it"),
            Map.entry("integration_message_lag_seconds_bucket", "the first event creates it, with its kind"),
            Map.entry("integration_message_lag_seconds_count", "the first event creates it, with its kind"),
            Map.entry("product_feedback_steps_total", "the first step creates it, with its outcome"),
            Map.entry("webhook_publish_total", "the first delivery creates it, with its provider and outcome"),
            Map.entry("webhook_rejected_total", "the first rejected delivery creates it, with its reason"));

    /** The series of a server at start. A meter that registers too many label sets at start grows past it. */
    private static final int SERIES_BUDGET = 3000;

    @Autowired
    private PrometheusMeterRegistry prometheus;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void shouldFindEveryRuleAndDashboardMetricInTheCode() throws IOException {
        Set<String> literals;
        try (Stream<Path> files = Files.walk(SOURCES)) {
            var literal = Pattern.compile("\"([a-z][a-z0-9]*(?:[._][a-z0-9]+)+)\"");
            literals = files.filter(file -> file.toString().endsWith(".java"))
                    .flatMap(file -> {
                        try {
                            return literal.matcher(Files.readString(file))
                                    .results()
                                    .map(match -> match.group(1));
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(name -> name.replace('.', '_'))
                    .collect(Collectors.toSet());
        }
        Set<String> read = new TreeSet<>(ruleMetrics());
        read.addAll(dashboardMetrics());
        Map<String, Set<String>> unknown = new TreeMap<>();
        for (String metric : read) {
            Set<String> families = families(metric);
            if (families.stream()
                    .noneMatch(family ->
                            literals.contains(family) || LIBRARY.stream().anyMatch(family::startsWith))) {
                unknown.put(metric, families);
            }
        }
        assertThat(unknown)
                .as("rule and dashboard metrics without a meter name in the code")
                .isEmpty();
    }

    @Test
    void shouldExportEveryRuleMetricThatTheServerRegistersAtStart() throws IOException {
        Set<String> exported = exportedNames(prometheus.scrape());
        Set<String> rules = ruleMetrics();
        Set<String> missing = new TreeSet<>();
        for (String metric : rules) {
            if (!exported.contains(metric) && !ABSENT_AT_START.containsKey(metric)) missing.add(metric);
        }
        assertThat(missing)
                .as("rule metrics that the server does not export at start")
                .isEmpty();
        assertThat(rules)
                .as("every exception still names a metric that a rule reads")
                .containsAll(ABSENT_AT_START.keySet());
    }

    @Test
    void shouldOfferTheBucketsThatTheRulesDivideByAndStayWithinTheSeriesBudget() throws IOException {
        webTestClient
                .get()
                .uri("/livez")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP");
        String scrape = prometheus.scrape();
        Set<String> exported = exportedNames(scrape);

        Map<String, Set<String>> bounds = ruleBucketBounds();
        assertThat(bounds).as("the rules divide by buckets").containsKeys("http_server_requests_seconds_bucket");
        bounds.forEach((family, les) -> {
            // A family that no event created yet has no buckets to check; the export test names why it is absent.
            if (!exported.contains(family) && ABSENT_AT_START.containsKey(family)) return;
            for (String le : les) {
                assertThat(scrape)
                        .as("%s offers le=%s, which a rule reads", family, le)
                        .containsPattern(
                                Pattern.compile("(?m)^" + family + "\\{[^}]*le=\"" + Pattern.quote(le) + "\""));
            }
        });
        long series = scrape.lines()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .count();
        assertThat(series).as("series of one scrape").isLessThanOrEqualTo(SERIES_BUDGET);
    }

    /** The names that the rules select with {@code job="hephaestus"}. */
    private static Set<String> ruleMetrics() throws IOException {
        Set<String> metrics = roleMetrics(Files.readString(RULES));
        assertThat(metrics).as("the rules select role metrics").hasSizeGreaterThan(30);
        return metrics;
    }

    /** The names that the dashboards' panels select with {@code job="hephaestus"}. */
    private static Set<String> dashboardMetrics() throws IOException {
        Set<String> metrics = new TreeSet<>();
        try (Stream<Path> files = Files.list(DASHBOARDS)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".json")).toList()) {
                // A panel's expression is a JSON string, so its label quotes are escaped.
                metrics.addAll(roleMetrics(Files.readString(file).replace("\\\"", "\"")));
            }
        }
        assertThat(metrics).as("the dashboards select role metrics").hasSizeGreaterThan(10);
        return metrics;
    }

    private static Set<String> roleMetrics(String text) {
        Set<String> metrics = new TreeSet<>();
        var selector =
                Pattern.compile("([a-zA-Z_:][a-zA-Z0-9_:]*)\\{([^}]*)\\}").matcher(text);
        while (selector.find()) {
            if (selector.group(2).contains("job=\"hephaestus\"")
                    && !selector.group(1).equals("up")) {
                metrics.add(selector.group(1));
            }
        }
        return metrics;
    }

    /**
     * The {@code le} values that the rules select on each bucket family, without {@code +Inf}. PromQL matches a label
     * by its text, so the scrape must offer the same text, not only the same number.
     */
    private static Map<String, Set<String>> ruleBucketBounds() throws IOException {
        Map<String, Set<String>> bounds = new TreeMap<>();
        var selector = Pattern.compile("([a-zA-Z_:][a-zA-Z0-9_:]*_bucket)\\{([^}]*)\\}")
                .matcher(Files.readString(RULES));
        var le = Pattern.compile("\\ble=\"([^\"]+)\"");
        while (selector.find()) {
            if (!selector.group(2).contains("job=\"hephaestus\"")) continue;
            var bound = le.matcher(selector.group(2));
            if (bound.find() && !bound.group(1).equals("+Inf")) {
                bounds.computeIfAbsent(selector.group(1), ignored -> new TreeSet<>())
                        .add(bound.group(1));
            }
        }
        return bounds;
    }

    /** The exposition name and the meter names it can come from: without the histogram and unit suffixes. */
    private static Set<String> families(String metric) {
        Set<String> names = new TreeSet<>(Set.of(metric));
        for (String suffix : List.of("_bucket", "_count", "_sum")) {
            if (metric.endsWith(suffix)) names.add(metric.substring(0, metric.length() - suffix.length()));
        }
        for (String name : Set.copyOf(names)) {
            for (String suffix : List.of("_total", "_seconds")) {
                if (name.endsWith(suffix)) names.add(name.substring(0, name.length() - suffix.length()));
            }
        }
        return names;
    }

    private static Set<String> exportedNames(String scrape) {
        return scrape.lines()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .map(line -> line.substring(0, line.indexOf('{') < 0 ? line.indexOf(' ') : line.indexOf('{')))
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
