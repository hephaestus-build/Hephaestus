package de.tum.cit.aet.hephaestus.agent.job;

import static de.tum.cit.aet.hephaestus.testconfig.TestEntities.workspace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgentJobTelemetryTest {

    @Test
    void shouldExposeOnlyBoundedLifecycleLabelsWhenRecordingTerminalJob() {
        var registry = new SimpleMeterRegistry();
        var telemetry = new AgentJobTelemetry(registry, Tracer.NOOP);
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
        assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(3);
        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTag("job.id")).isNull());
    }

    @Test
    @SuppressWarnings("try") // The scope makes the span current until this block ends.
    void shouldExportARealExecutionSpanWithoutInventingASubmissionParent() {
        var exporter = mock(SpanExporter.class);
        when(exporter.export(any())).thenReturn(CompletableResultCode.ofSuccess());
        when(exporter.shutdown()).thenReturn(CompletableResultCode.ofSuccess());
        try (var provider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            var tracer = new OtelTracer(provider.get("test"), new OtelCurrentTraceContext(), event -> {});
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
            verify(exporter).export(argThat(spans -> {
                var data = spans.iterator().next();
                return data.getName().equals("practice_review.execute")
                        && data.getParentSpanId().equals("0".repeat(16))
                        && data.getEndEpochNanos() >= data.getStartEpochNanos()
                        && job.getId()
                                .toString()
                                .equals(data.getAttributes().get(AttributeKey.stringKey("hephaestus.job.id")));
            }));
        }
    }
}
