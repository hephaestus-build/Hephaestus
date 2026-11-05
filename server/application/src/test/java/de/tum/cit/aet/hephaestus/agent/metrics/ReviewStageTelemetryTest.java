package de.tum.cit.aet.hephaestus.agent.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ReviewStageTelemetryTest extends BaseUnitTest {

    private final List<SpanData> finished = new CopyOnWriteArrayList<>();
    private final SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(new SpanExporter() {
                @Override
                public CompletableResultCode export(Collection<SpanData> spans) {
                    finished.addAll(spans);
                    return CompletableResultCode.ofSuccess();
                }

                @Override
                public CompletableResultCode flush() {
                    return CompletableResultCode.ofSuccess();
                }

                @Override
                public CompletableResultCode shutdown() {
                    return CompletableResultCode.ofSuccess();
                }
            }))
            .build();
    private final OtelCurrentTraceContext current = new OtelCurrentTraceContext();
    private final Tracer tracer = new OtelTracer(
            provider.get("test"), current, event -> {}, new OtelBaggageManager(current, List.of(), List.of()));
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ReviewStageTelemetry stages = new ReviewStageTelemetry(registry, tracer);

    @AfterEach
    void close() {
        provider.close();
    }

    private Timer timer(String stage, String outcome) {
        return registry.get(AgentMetrics.AGENT_JOB_STAGE_DURATION)
                .tags("stage", stage, "outcome", outcome)
                .timer();
    }

    @Test
    void shouldExportEveryStageAndOutcomeAtZeroBeforeTheFirstAttempt() {
        assertThat(registry.get(AgentMetrics.AGENT_JOB_STAGE_DURATION).timers())
                .hasSize(4 * 4)
                .allSatisfy(timer -> assertThat(timer.count()).isZero());
    }

    // Closing the scope is the operation; its binding is intentionally unread.
    @SuppressWarnings("try")
    @Test
    void shouldRecordAStageOnceAsAChildSpanOfTheExecution() {
        Span execution = tracer.nextSpan().name("practice_review.execute").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(execution)) {
            var composition = stages.start(ReviewStageTelemetry.Stage.COMPOSITION);
            assertThat(tracer.currentSpan()).isNotEqualTo(execution);
            composition.end(ReviewStageTelemetry.Outcome.HANDED_ON);
            composition.end(ReviewStageTelemetry.Outcome.OK);
            assertThat(tracer.currentSpan()).isEqualTo(execution);
        } finally {
            execution.end();
        }

        assertThat(timer("composition", "handed_on").count()).isEqualTo(1);
        assertThat(timer("composition", "ok").count()).isZero();
        var span = finished.stream()
                .filter(data -> data.getName().equals("practice_review.composition"))
                .findFirst()
                .orElseThrow();
        assertThat(span.getParentSpanId()).isEqualTo(execution.context().spanId());
        assertThat(span.getAttributes().get(AttributeKey.stringKey("hephaestus.review.stage.outcome")))
                .isEqualTo("handed_on");
    }

    @Test
    void shouldEndTheSpanOfAStageThatDidNotRunWithoutASample() {
        var sandbox = stages.start(ReviewStageTelemetry.Stage.SANDBOX);
        sandbox.discard();
        sandbox.end(ReviewStageTelemetry.Outcome.FAILED);

        assertThat(registry.get(AgentMetrics.AGENT_JOB_STAGE_DURATION).timers())
                .allSatisfy(timer -> assertThat(timer.count()).isZero());
        assertThat(finished).extracting(SpanData::getName).containsExactly("practice_review.sandbox");
        assertThat(tracer.currentSpan()).isNull();
    }
}
