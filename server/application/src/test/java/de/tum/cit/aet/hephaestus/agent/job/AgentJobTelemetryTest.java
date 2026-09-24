package de.tum.cit.aet.hephaestus.agent.job;

import static de.tum.cit.aet.hephaestus.testconfig.TestEntities.workspace;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgentJobTelemetryTest {

    @Test
    void shouldExposeOnlyBoundedLifecycleLabelsWhenRecordingTerminalJob() {
        var registry = new SimpleMeterRegistry();
        var telemetry = new AgentJobTelemetry(registry, io.micrometer.tracing.Tracer.NOOP);
        var job = new AgentJob();
        job.prePersist();
        job.setWorkspace(workspace(42L));
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);

        telemetry.terminal(job, AgentJobStatus.COMPLETED, Duration.ofSeconds(3));

        assertThat(registry.get("agent.job.total")
                        .tag("outcome", "completed")
                        .counter()
                        .count())
                .isEqualTo(1);
        var timer = registry.get("agent.job.duration").tag("phase", "total").timer();
        assertThat(timer.getId().getTags()).extracting(tag -> tag.getKey()).containsExactly("phase");
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(3);
        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTag("job.id")).isNull());
    }

    @Test
    @SuppressWarnings("try") // The scope makes the span current until this block ends.
    void shouldExportARealExecutionSpanWithoutInventingASubmissionParent() {
        var exporter = org.mockito.Mockito.mock(io.opentelemetry.sdk.trace.export.SpanExporter.class);
        org.mockito.Mockito.when(exporter.export(org.mockito.ArgumentMatchers.any()))
                .thenReturn(io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess());
        org.mockito.Mockito.when(exporter.shutdown())
                .thenReturn(io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess());
        try (var provider = io.opentelemetry.sdk.trace.SdkTracerProvider.builder()
                .setSampler(io.opentelemetry.sdk.trace.samplers.Sampler.alwaysOn())
                .addSpanProcessor(io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(exporter))
                .build()) {
            var tracer = new io.micrometer.tracing.otel.bridge.OtelTracer(
                    provider.get("test"), new io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext(), event -> {});
            var telemetry = new AgentJobTelemetry(new SimpleMeterRegistry(), tracer);
            var job = new AgentJob();
            job.prePersist();
            job.setWorkspace(workspace(42L));
            job.setTraceId("a".repeat(32));
            var span = telemetry.startExecution(job);
            try (var scope = telemetry.executionScope(span)) {
                assertThat(tracer.currentSpan()).isNotNull();
                assertThat(span.context().traceId()).matches("[a-f0-9]{32}").isNotEqualTo(job.getTraceId());
                assertThat(span.context().sampled()).isTrue();
            } finally {
                span.end();
            }
            org.mockito.Mockito.verify(exporter).export(org.mockito.ArgumentMatchers.argThat(spans -> {
                var data = spans.iterator().next();
                return data.getName().equals("practice_review.execute")
                        && data.getParentSpanId().equals("0".repeat(16))
                        && data.getEndEpochNanos() >= data.getStartEpochNanos()
                        && job.getId()
                                .toString()
                                .equals(data.getAttributes()
                                        .get(io.opentelemetry.api.common.AttributeKey.stringKey("hephaestus.job.id")));
            }));
        }
    }
}
