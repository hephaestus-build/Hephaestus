package de.tum.cit.aet.hephaestus.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TracingDependencyTest {

    @Test
    void shouldIncludeTheOfficialOtlpExporterWithoutLegacyExporters() {
        assertThat(isPresent("io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter"))
                .isTrue();
        assertThat(isPresent("io.opentelemetry.exporter.zipkin.ZipkinSpanExporter"))
                .isFalse();
        assertThat(isPresent("io.opentelemetry.exporter.jaeger.JaegerGrpcSpanExporter"))
                .isFalse();
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, TracingDependencyTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
