package de.tum.cit.aet.hephaestus.observability;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import java.util.stream.Collectors;
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
}
