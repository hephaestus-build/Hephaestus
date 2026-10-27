package de.tum.cit.aet.hephaestus.agent.job;

import static de.tum.cit.aet.hephaestus.testconfig.TestEntities.workspace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.observability.ShippedLogEncoder;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class ReviewJobTelemetryTest {

    @Test
    void shouldLogTheExceptionClassAndCauseChainOnceWithoutPrivateMessages() {
        var logger = (Logger) LoggerFactory.getLogger(ReviewJobTelemetry.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var telemetry = new ReviewJobTelemetry(new SimpleMeterRegistry(), Tracer.NOOP);
            var job = new ReviewJob();
            RuntimeJobTestData.initialize(job, workspace(42L).getId());
            var cause = new IllegalArgumentException("provider body with token=secret");
            var exception = new IllegalStateException("https://private:secret@provider/repo", cause);
            var failure = PreparationFailure.of(PreparationFailure.Stage.INPUTS, exception);

            telemetry.preparationFailed(job, failure, "FAILED");

            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).isEqualTo("Agent job preparation failed");
                assertThat(event.getKeyValuePairs())
                        .anySatisfy(pair -> {
                            assertThat(pair.key).isEqualTo("exception.type");
                            assertThat(pair.value).isEqualTo(IllegalStateException.class.getName());
                        })
                        .anySatisfy(pair -> {
                            assertThat(pair.key).isEqualTo("exception.cause.types");
                            assertThat(pair.value).isEqualTo(List.of(IllegalArgumentException.class.getName()));
                        });
                assertThat(event.getKeyValuePairs().toString()).doesNotContain("secret", "provider body");
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** The executor writes lifecycle events inside the job's MDC scope; each reaches the record with all its fields. */
    @Test
    void shouldWriteEveryLifecycleFieldWhenTheEventRunsInsideTheJobsMdc() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(ReviewJobTelemetry.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace(42L).getId());
        MDC.put(StructuredLogKeys.JOB_ID, job.getId().toString());
        MDC.put(StructuredLogKeys.WORKSPACE_ID, "42");
        try {
            var telemetry = new ReviewJobTelemetry(new SimpleMeterRegistry(), Tracer.NOOP);
            telemetry.transition(
                    job,
                    "agent.job.started",
                    ReviewJobTelemetry.Phase.QUEUE,
                    ReviewJobTelemetry.ResultOutcome.STARTED,
                    Duration.ofSeconds(5));
            telemetry.terminal(job, ReviewJobStatus.COMPLETED, Duration.ofSeconds(9));
            telemetry.preparationFailed(
                    job, PreparationFailure.of(PreparationFailure.Stage.INPUTS, new IllegalStateException()), "FAILED");

            assertThat(appender.list).hasSize(3);
            var records = new ArrayList<JsonNode>();
            for (ILoggingEvent event : appender.list) {
                records.add(new ObjectMapper()
                        .readTree(ShippedLogEncoder.encode(event))
                        .get("context"));
            }
            assertThat(records)
                    .extracting(context -> context.at("/event/name").asString())
                    .containsExactly("agent.job.started", "agent.job.terminal", "agent.job.preparation_failed");
            assertThat(records).allSatisfy(context -> {
                assertThat(context.at("/job/id").asString())
                        .isEqualTo(job.getId().toString());
                assertThat(context.at("/workspace/id").asLong()).isEqualTo(42L);
                assertThat(context.at("/review/id").asString())
                        .isEqualTo(job.getReviewId().toString());
            });
            assertThat(records.get(1).at("/job/outcome").asString()).isEqualTo("completed");
            assertThat(records.get(1).at("/duration/ms").asLong()).isEqualTo(9000L);
            assertThat(records.get(2).at("/job/preparation/stage").asString()).isEqualTo("inputs");
        } finally {
            MDC.remove(StructuredLogKeys.JOB_ID);
            MDC.remove(StructuredLogKeys.WORKSPACE_ID);
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void shouldExposeOnlyBoundedLifecycleLabelsWhenRecordingTerminalJob() {
        var registry = new SimpleMeterRegistry();
        var telemetry = new ReviewJobTelemetry(registry, Tracer.NOOP);
        var job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace(42L).getId());
        job.setTraceId("a".repeat(32));

        telemetry.terminal(job, ReviewJobStatus.COMPLETED, Duration.ofSeconds(3));

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
            var telemetry = new ReviewJobTelemetry(new SimpleMeterRegistry(), tracer);
            var job = new ReviewJob();
            RuntimeJobTestData.initialize(job, workspace(42L).getId());
            job.setTraceId("a".repeat(32));
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

    @Test
    @SuppressWarnings("try") // The scope makes the span current until this block ends.
    void shouldTagTheExecutionSpanWithOnlyTheClosedPreparationStageKindAndReason() {
        var exporter = mock(SpanExporter.class);
        when(exporter.export(any())).thenReturn(CompletableResultCode.ofSuccess());
        when(exporter.shutdown()).thenReturn(CompletableResultCode.ofSuccess());
        try (var provider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            var tracer = new OtelTracer(provider.get("test"), new OtelCurrentTraceContext(), event -> {});
            var telemetry = new ReviewJobTelemetry(new SimpleMeterRegistry(), tracer);
            var job = new ReviewJob();
            RuntimeJobTestData.initialize(job, workspace(42L).getId());
            job.setTraceId("a".repeat(32));

            var failure = PreparationFailure.of(
                    PreparationFailure.Stage.PROVENANCE,
                    new IllegalStateException("jdbc:postgresql://db/hephaestus password=secret"));
            var span = telemetry.startExecution(job);
            try (var scope = telemetry.executionScope(span)) {
                telemetry.preparationFailed(job, failure, "FAILED");
            } finally {
                span.end();
            }
            verify(exporter).export(argThat(spans -> {
                var attributes = spans.iterator().next().getAttributes();
                return "provenance".equals(attributes.get(AttributeKey.stringKey("hephaestus.job.preparation.stage")))
                        && "unknown".equals(attributes.get(AttributeKey.stringKey("hephaestus.job.preparation.kind")))
                        && "unclassified"
                                .equals(attributes.get(AttributeKey.stringKey("hephaestus.job.preparation.reason")))
                        && !attributes.toString().contains("secret");
            }));
        }
    }
}
