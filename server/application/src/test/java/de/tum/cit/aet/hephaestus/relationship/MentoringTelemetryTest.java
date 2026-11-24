package de.tum.cit.aet.hephaestus.relationship;

import static de.tum.cit.aet.hephaestus.relationship.MentoringLogEvent.OPERATION_FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.relationship.MentoringFieldClass.Sink;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

@Tag("unit")
class MentoringTelemetryTest {

    @ParameterizedTest
    @EnumSource(Sink.class)
    void shouldRefuseContentAndPersonIdentifiersAtEverySink(Sink sink) {
        assertThatThrownBy(() -> MentoringFieldClass.CONTENT.requireAllowed(sink))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MentoringFieldClass.PERSON_IDENTIFIER.requireAllowed(sink))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(MentoringLogKey.class)
    void shouldDeclareOnlyAllowedLogAndSpanFields(MentoringLogKey key) {
        key.fieldClass().requireAllowed(Sink.LOG);
        key.fieldClass().requireAllowed(Sink.TRACE);
    }

    @Test
    void shouldExportOnlyDeclaredSpanAttributes() {
        var exporter = mock(SpanExporter.class);
        when(exporter.export(anyCollection())).thenReturn(CompletableResultCode.ofSuccess());
        when(exporter.shutdown()).thenReturn(CompletableResultCode.ofSuccess());
        try (var provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            var context = new OtelCurrentTraceContext();
            var tracer = new OtelTracer(
                    provider.get("test"), context, event -> {}, new OtelBaggageManager(context, List.of(), List.of()));
            var trace = new MentoringTrace(tracer);
            var run = UUID.randomUUID();
            assertThatThrownBy(() -> trace.start(
                            OPERATION_FAILED, MentoringLogField.of(MentoringLogKey.RUN_ID, "a person's login")))
                    .isInstanceOf(IllegalArgumentException.class);

            try (var _ = trace.start(
                    OPERATION_FAILED,
                    MentoringLogField.of(MentoringLogKey.RUN_ID, run),
                    MentoringLogField.of(MentoringLogKey.DURATION, Duration.ofMillis(73)))) {
                assertThat(tracer.currentSpan()).isNotNull();
            }
            assertThat(tracer.currentSpan()).isNull();

            var expected = Attributes.of(
                    AttributeKey.stringKey("run.id"),
                    run.toString(),
                    AttributeKey.longKey("mentoring.duration_ms"),
                    73L);
            ArgumentCaptor<Collection<SpanData>> spans = ArgumentCaptor.captor();
            verify(exporter).export(spans.capture());
            assertThat(spans.getValue()).singleElement().satisfies(span -> {
                assertThat(span.getName()).isEqualTo("mentoring.operation.failed");
                assertThat(span.getAttributes().asMap()).isEqualTo(expected.asMap());
            });
        }
    }

    @Test
    void shouldCountEventsWithOnlyTheClosedKindLabel() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new MentoringEventMetrics(registry);
            var eventKinds =
                    Arrays.stream(MentoringLogEvent.values()).map(Enum::name).toList();
            assertThat(registry.getMeters()).allSatisfy(meter -> {
                assertThat(meter.getId().getName()).isEqualTo("mentoring.events");
                assertThat(meter.getId().getTags())
                        .singleElement()
                        .satisfies(label -> assertThat(label.getKey()).isEqualTo("kind"));
            });
            assertThat(registry.getMeters())
                    .extracting(meter -> meter.getId().getTag("kind"))
                    .containsExactlyInAnyOrderElementsOf(eventKinds);
            var counter = registry.get("mentoring.events")
                    .tag("kind", OPERATION_FAILED.name())
                    .counter();
            assertThat(counter.count()).isZero();

            metrics.count(OPERATION_FAILED);

            assertThat(counter.count()).isEqualTo(1);
            assertThat(registry.getMeters()).hasSize(eventKinds.size());
        } finally {
            registry.close();
        }
    }

    @Test
    void shouldRefuseCorrelationHandlesAndUnboundedNumbersAsMetricLabels() {
        assertThatThrownBy(() -> MentoringLogField.of(MentoringLogKey.RUN_ID, UUID.randomUUID())
                        .metricLabel())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CORRELATION_HANDLE");
        assertThatThrownBy(
                        () -> MentoringLogField.of(MentoringLogKey.RUN_EPOCH, 3).metricLabel())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounded value");
    }
}
