package de.tum.cit.aet.hephaestus.performance;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.scan.ScanReportTestData;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.SharedTestDoubles;
import de.tum.cit.aet.hephaestus.testconfig.SqlRequestTrace;
import de.tum.cit.aet.hephaestus.testconfig.StubInstallationRepositoryEnumerator;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Run with HEPHAESTUS_API_READ_COST=true and :application:integrationTest --tests
 * '*SignedInApiReadCostIntegrationTest'. The test report's stdout contains one row per route and fixture size.
 */
@Import({
    SignedInApiReadCostData.class,
    SharedTestDoubles.class,
    ReviewIntegrationData.class,
    ScanReportTestData.class,
    StubInstallationRepositoryEnumerator.class
})
@TestPropertySource(properties = "hephaestus.llm.display-currency=")
@EnabledIfEnvironmentVariable(named = "HEPHAESTUS_API_READ_COST", matches = "true")
class SignedInApiReadCostIntegrationTest extends RealAuthIntegrationTest {
    @Autowired
    private WebTestClient defaultHttp;

    @Autowired
    private SqlRequestTrace trace;

    @Autowired
    private SignedInApiReadCostData data;

    @Test
    void shouldMeasureSignedInGetRoutesAtBothWorkspaceSizes() throws IOException {
        var routes = routes();
        var http = defaultHttp
                .mutate()
                .responseTimeout(Duration.ofMinutes(2))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        var small = data.seed(12, 6);
        var smallCosts = measure(http, "small", routes, small);
        // Separate workspaces keep first-read side effects and warm state from passing between fixture sizes.
        var large = data.seed(400, 40);
        var largeCosts = measure(http, "large", routes, large);
        assertThat(largeCosts.keySet()).isEqualTo(smallCosts.keySet());
        assertThat(largeCosts).isNotEmpty();
    }

    private Map<Route, Long> measure(
            WebTestClient http, String scale, List<Route> routes, SignedInApiReadCostData.Fixture fixture) {
        Map<Route, Long> costs = new HashMap<>();
        for (var route : routes) {
            if (!fixture.supports(route.path())) {
                System.out.printf("READ_COST_UNAVAILABLE\t%s\t%s%n", scale, route.operation());
                continue;
            }
            var first = sample(http, route, fixture);
            if (first.status() < 200 || first.status() >= 300) {
                System.out.printf(
                        "READ_COST_STATUS\t%s\t%s\t%d\t%d%n",
                        scale, route.operation(), first.status(), first.sql().size());
                continue;
            }
            var warm = IntStream.range(0, 3)
                    .mapToObj(index -> sample(http, route, fixture))
                    .toList();
            assertThat(warm).extracting(Sample::status).as(route.operation()).containsOnly(first.status());
            long statements = warm.stream()
                    .mapToLong(sample -> sample.sql().size())
                    .sorted()
                    .toArray()[1];
            long micros = warm.stream().mapToLong(Sample::micros).sorted().toArray()[1];
            costs.put(route, statements);
            System.out.printf(
                    Locale.ROOT,
                    "READ_COST\t%s\t%s\t%d\t%.3f\t%d\t%.3f\t%d%n",
                    scale,
                    route.operation(),
                    statements,
                    micros / 1000.0,
                    first.sql().size(),
                    first.micros() / 1000.0,
                    first.bytes());
            if (scale.equals("large")) {
                printRepeatedSql(
                        route,
                        warm.stream()
                                .filter(sample -> sample.sql().size() == statements)
                                .findFirst()
                                .orElseThrow());
            }
        }
        for (String bootstrap :
                List.of("getCurrentUser", "listWorkspaces", "getMemberOnboarding", "getOwnMembership")) {
            assertThat(costs.entrySet())
                    .as("signed-in bootstrap: " + bootstrap)
                    .anyMatch(entry -> entry.getKey().operation().equals(bootstrap) && entry.getValue() > 0);
        }
        return costs;
    }

    private Sample sample(WebTestClient http, Route route, SignedInApiReadCostData.Fixture fixture) {
        long start = System.nanoTime();
        try {
            var measured = trace.measure(
                    http,
                    client -> client.get()
                            .uri(fixture.uri(route.path()))
                            .cookie(AuthProperties.DEFAULT_COOKIE_NAME, fixture.token(route.path()))
                            .exchange()
                            .expectBody()
                            .returnResult());
            long micros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start);
            byte[] body = measured.value().getResponseBody();
            return new Sample(
                    measured.value().getStatus().value(), micros, body == null ? 0 : body.length, measured.sql());
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Read-cost sample failed for " + route.operation(), failure);
        }
    }

    private static void printRepeatedSql(Route route, Sample sample) {
        sample.sql().stream()
                .map(sql -> sql.replaceAll("\\s+", " ").strip())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet()
                .stream()
                .filter(entry -> entry.getValue() > 1)
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(12)
                .forEach(entry -> System.out.printf(
                        "READ_COST_SQL\t%s\t%d\t%s%n", route.operation(), entry.getValue(), entry.getKey()));
    }

    private static List<Route> routes() throws IOException {
        try (var input = Objects.requireNonNull(SignedInApiReadCostIntegrationTest.class.getResourceAsStream(
                        "/performance/signed-in-get-routes.tsv"));
                var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(line -> line.split("\\t", 2))
                    .map(fields -> new Route(fields[0], fields[1]))
                    .toList();
        }
    }

    private record Route(String operation, String path) {}

    private record Sample(int status, long micros, int bytes, List<String> sql) {}
}
