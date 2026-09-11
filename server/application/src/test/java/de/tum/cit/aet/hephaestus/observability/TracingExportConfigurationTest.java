package de.tum.cit.aet.hephaestus.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

@Tag("unit")
class TracingExportConfigurationTest {
    @Test
    void shouldKeepAllOtlpSignalsOffUnlessTracesAreExplicitlyEnabled() throws IOException {
        var defaults = environment(Map.of());
        assertThat(defaults.getRequiredProperty("management.tracing.export.otlp.enabled", Boolean.class))
                .isFalse();
        assertThat(defaults.getRequiredProperty("management.tracing.sampling.probability", Double.class))
                .isZero();
        assertThat(defaults.getRequiredProperty("management.otlp.metrics.export.enabled", Boolean.class))
                .isFalse();
        assertThat(defaults.getRequiredProperty("management.logging.export.otlp.enabled", Boolean.class))
                .isFalse();

        var evaluation = environment(Map.of(
                "TRACING_OTLP_ENABLED",
                "true",
                "TRACING_SAMPLING_PROBABILITY",
                "1.0",
                "TRACING_OTLP_ENDPOINT",
                "http://collector:4318/v1/traces"));
        assertThat(evaluation.getRequiredProperty("management.tracing.export.otlp.enabled", Boolean.class))
                .isTrue();
        assertThat(evaluation.getRequiredProperty("management.tracing.sampling.probability", Double.class))
                .isEqualTo(1.0);
        assertThat(evaluation.getRequiredProperty("management.opentelemetry.tracing.export.otlp.endpoint"))
                .isEqualTo("http://collector:4318/v1/traces");
        assertThat(evaluation.getRequiredProperty("management.otlp.metrics.export.enabled", Boolean.class))
                .isFalse();
        assertThat(evaluation.getRequiredProperty("management.logging.export.otlp.enabled", Boolean.class))
                .isFalse();
    }

    private static StandardEnvironment environment(Map<String, Object> overrides) throws IOException {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("evaluation", overrides));
        new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        return environment;
    }
}
