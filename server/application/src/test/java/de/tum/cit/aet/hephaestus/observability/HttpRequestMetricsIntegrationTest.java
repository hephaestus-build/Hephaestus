package de.tum.cit.aet.hephaestus.observability;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsProperties;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class HttpRequestMetricsIntegrationTest extends BaseIntegrationTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Autowired
    @Qualifier("metricsHttpServerUriTagFilter")
    private MeterFilter uriFilter;

    @Autowired
    private MetricsProperties metricsProperties;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void shouldRetainMetricsForEveryRegisteredRouteTemplate() {
        var templates = mappings.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream())
                .collect(Collectors.toSet());

        assertThat(templates.size()).isGreaterThan(100);
        // Leave room for the framework's bounded UNKNOWN/NOT_FOUND/REDIRECTION/root tags and Actuator.
        assertThat(metricsProperties.getWeb().getServer().getMaxUriTags()).isGreaterThan(templates.size() + 8);
        var registry = new SimpleMeterRegistry();
        try {
            registry.config().meterFilter(uriFilter);
            for (String template : templates) {
                registry.timer("http.server.requests", "uri", template).record(() -> {});
                assertThat(registry.find("http.server.requests")
                                .tag("uri", template)
                                .timer())
                        .as("metrics are retained for %s", template)
                        .isNotNull();
            }
        } finally {
            registry.close();
        }
    }

    @Test
    void shouldNotUseUnmatchedRequestPathsAsMetricTags() {
        String marker = UUID.randomUUID().toString();
        for (int i = 0; i < 3; i++) {
            webTestClient
                    .get()
                    .uri("/not-a-route/" + marker + "/" + i)
                    .exchange()
                    .expectStatus()
                    .is4xxClientError()
                    .expectBody(Void.class);
        }
        assertThat(meterRegistry.find("http.server.requests").timers())
                .isNotEmpty()
                .noneSatisfy(timer -> assertThat(timer.getId().getTag("uri")).contains(marker));
    }

    @Test
    void shouldCountRequestsWithinTheTwoSecondReadObjective() {
        webTestClient
                .get()
                .uri("/not-a-route/" + UUID.randomUUID())
                .exchange()
                .expectStatus()
                .is4xxClientError()
                .expectBody(Void.class);
        // The API read objective (hephaestus-alerts.yml, group hephaestus-slos) reads the bucket at 2 s.
        assertThat(meterRegistry.find("http.server.requests").timers())
                .isNotEmpty()
                .allSatisfy(timer -> assertThat(within(timer.takeSnapshot())).isNotNull());

        Timer probe = meterRegistry.timer("http.server.requests", "uri", "/slo-probe/" + UUID.randomUUID());
        probe.record(Duration.ofMillis(100));
        probe.record(Duration.ofSeconds(3));

        HistogramSnapshot snapshot = probe.takeSnapshot();
        assertThat(snapshot.count()).isEqualTo(2);
        CountAtBucket bucket = within(snapshot);
        assertThat(bucket).isNotNull();
        assertThat(bucket.count()).isEqualTo(1.0);
    }

    private static @Nullable CountAtBucket within(HistogramSnapshot snapshot) {
        for (CountAtBucket bucket : snapshot.histogramCounts()) {
            if (bucket.bucket(TimeUnit.SECONDS) == 2.0) return bucket;
        }
        return null;
    }
}
