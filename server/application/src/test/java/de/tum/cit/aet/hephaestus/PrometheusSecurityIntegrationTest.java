package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.config.CorsProperties;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.info.InfoEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.AvailabilityProbesAutoConfiguration;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.application.AvailabilityHealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsEndpointAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.tomcat.autoconfigure.actuate.web.server.TomcatServletManagementContextAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.actuate.endpoint.web.WebMvcHealthEndpointExtensionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Real listeners and shipped role overlays, without unrelated database or provider infrastructure. */
@Tag("integration")
class PrometheusSecurityIntegrationTest {

    @ParameterizedTest
    @CsvSource({"server,true", "server,false", "worker,true", "worker,false", "webhook,true", "webhook,false"})
    void shouldServeMetricsOnlyOnManagementListenerWhenRoleStarts(String role, boolean decoderEnabled)
            throws Exception {
        var application = new SpringApplication(MetricsApplication.class);
        application.setAdditionalProfiles(role);
        try (var context = application.run(
                        "--server.port=0",
                        "--server.address=127.0.0.1",
                        "--management.server.port=0",
                        "--management.server.address=${test.management.host}",
                        "--test.management.host=::1",
                        "--test.decoder-enabled=" + decoderEnabled,
                        "--hephaestus.runtime.server.enabled=" + role.equals("server"),
                        "--hephaestus.runtime.worker.enabled=" + role.equals("worker"),
                        "--hephaestus.runtime.webhook.enabled=" + role.equals("webhook"),
                        "--spring.main.banner-mode=off");
                var client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .build()) {
            var environment = context.getEnvironment();
            int managementPort = environment.getRequiredProperty("local.management.port", Integer.class);
            int applicationPort = environment.getRequiredProperty("local.server.port", Integer.class);
            assertThat(managementPort).isNotEqualTo(applicationPort);
            assertThat(environment.getRequiredProperty("management.server.address"))
                    .isEqualTo("::1");
            var registry = context.getBean(MeterRegistry.class);
            String listenerId = registerListenerProbe(registry);
            registry.counter(IntegrationCoreMetrics.INTEGRATION_CONSUMER_POISON, "kind", "github")
                    .increment();
            registry.counter(AgentMetrics.LLM_BUDGET_EXHAUSTED).increment();
            registry.counter(AgentMetrics.LLM_BUDGET_BLOCKED, "surface", "agent_job", "cap", "instance")
                    .increment();
            registry.counter(AgentMetrics.AGENT_JOB_TOTAL, "outcome", "failed").increment();
            Gauge.builder(IntegrationCoreMetrics.WEBHOOK_STREAM_POLL_AGE, () -> 0.0)
                    .baseUnit("seconds")
                    .tag("stream", "github")
                    .register(registry);

            var scrape = send(client, "::1", managementPort, "/actuator/prometheus", "GET", false);
            assertThat(scrape.statusCode()).isEqualTo(200);
            assertThat(scrape.body())
                    .contains(
                            "integration_consumer_poison_total",
                            "kind=\"github\"",
                            "llm_budget_exhausted_total",
                            "llm_budget_blocked_total",
                            "agent_job_total",
                            "webhook_stream_poll_age_seconds",
                            listenerId);
            assertNoListenerProbe(client, "127.0.0.1", managementPort, listenerId);
            // Irrelevant or expired user credentials must not break a scraper.
            assertThat(send(client, "::1", managementPort, "/actuator/prometheus", "GET", true)
                            .statusCode())
                    .isEqualTo(200);
            assertThat(send(client, applicationPort, "/actuator/prometheus", "GET", false)
                            .statusCode())
                    .isEqualTo(403);
            assertThat(send(client, applicationPort, "/actuator/prometheus", "GET", true)
                            .statusCode())
                    .isEqualTo(403);
            assertThat(send(client, "::1", managementPort, "/actuator/prometheus", "POST", false)
                            .statusCode())
                    .isEqualTo(403);
            assertThat(send(client, "::1", managementPort, "/actuator/metrics", "GET", false)
                            .statusCode())
                    .isBetween(400, 499);
            if (decoderEnabled) {
                assertThat(send(client, "::1", managementPort, "/actuator/metrics", "GET", true)
                                .statusCode())
                        .isEqualTo(200);
            }
            for (int port : List.of(applicationPort, managementPort)) {
                assertThat(send(
                                        client,
                                        port == managementPort ? "::1" : "127.0.0.1",
                                        port,
                                        port == applicationPort ? "/livez" : "/actuator/health/liveness",
                                        "GET",
                                        false)
                                .statusCode())
                        .isEqualTo(200);
                assertThat(send(
                                        client,
                                        port == managementPort ? "::1" : "127.0.0.1",
                                        port,
                                        port == applicationPort ? "/readyz" : "/actuator/health/readiness",
                                        "GET",
                                        false)
                                .statusCode())
                        .isEqualTo(200);
            }
        }
    }

    @Test
    void shouldKeepDefaultManagementListenerOnLoopback() throws Exception {
        var application = new SpringApplication(MetricsApplication.class);
        try (var context = application.run(
                        "--server.port=0",
                        "--server.address=127.0.0.1",
                        "--management.server.port=0",
                        "--spring.main.banner-mode=off");
                var client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .build()) {
            int managementPort = context.getEnvironment().getRequiredProperty("local.management.port", Integer.class);
            String listenerId = registerListenerProbe(context.getBean(MeterRegistry.class));
            var scrape = send(client, managementPort, "/actuator/prometheus", "GET", false);
            assertThat(scrape.statusCode()).isEqualTo(200);
            assertThat(scrape.body()).contains(listenerId);
            assertNoListenerProbe(client, "::1", managementPort, listenerId);
        }
    }

    private static String registerListenerProbe(MeterRegistry registry) {
        String listenerId = UUID.randomUUID().toString();
        registry.counter("test_management_listener", "context", listenerId).increment();
        return listenerId;
    }

    private static void assertNoListenerProbe(HttpClient client, String host, int port, String listenerId)
            throws Exception {
        try {
            var response = send(client, host, port, "/actuator/prometheus", "GET", false);
            assertThat(response.body())
                    .as("HTTP %s on %s must not expose this listener's metrics", response.statusCode(), host)
                    .doesNotContain(listenerId);
        } catch (IOException ignored) {
            // Different bind addresses can share a port; its vacancy is not this listener's boundary.
        }
    }

    private static HttpResponse<String> send(HttpClient client, int port, String path, String method, boolean token)
            throws Exception {
        return send(client, "127.0.0.1", port, path, method, token);
    }

    private static HttpResponse<String> send(
            HttpClient client, String host, int port, String path, String method, boolean token) throws Exception {
        var request = HttpRequest.newBuilder(new URI("http", null, host, port, path, null, null))
                .timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (token) {
            request.header("Authorization", "Bearer user-token");
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(SecurityConfig.class)
    @ImportAutoConfiguration({
        TomcatServletWebServerAutoConfiguration.class,
        DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        JacksonAutoConfiguration.class,
        SecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class,
        EndpointAutoConfiguration.class,
        WebEndpointAutoConfiguration.class,
        ManagementContextAutoConfiguration.class,
        TomcatServletManagementContextAutoConfiguration.class,
        WebMvcHealthEndpointExtensionAutoConfiguration.class,
        HealthContributorRegistryAutoConfiguration.class,
        HealthEndpointAutoConfiguration.class,
        AvailabilityProbesAutoConfiguration.class,
        AvailabilityHealthContributorAutoConfiguration.class,
        ApplicationAvailabilityAutoConfiguration.class,
        InfoEndpointAutoConfiguration.class,
        MetricsAutoConfiguration.class,
        MetricsEndpointAutoConfiguration.class,
        PrometheusMetricsExportAutoConfiguration.class,
    })
    static class MetricsApplication {

        @Bean
        CorsProperties corsProperties() {
            return new CorsProperties(List.of("http://localhost"));
        }

        @Bean
        @ConditionalOnProperty(name = "test.decoder-enabled", havingValue = "true")
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "ES256")
                    .claim("sub", "operator")
                    .claim("roles", List.of("app_admin"))
                    .build();
        }
    }
}
