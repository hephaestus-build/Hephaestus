package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.tum.cit.aet.hephaestus.integration.core.handler.ProviderEvent;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** A provider call that a handler makes belongs to the trace of the webhook request that published the event. */
class EventTraceContextTest extends BaseUnitTest {

    private static final String GRAPHQL = "https://api.github.com/graphql";

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
    private final io.opentelemetry.api.trace.Tracer otelTracer = provider.get("test");
    private final OtelCurrentTraceContext current = new OtelCurrentTraceContext();
    private final Tracer tracer =
            new OtelTracer(otelTracer, current, event -> {}, new OtelBaggageManager(current, List.of(), List.of()));
    private final Propagator propagator =
            new OtelPropagator(ContextPropagators.create(W3CTraceContextPropagator.getInstance()), otelTracer);
    private final EventTraceContext traceContext = traceContext();

    @AfterEach
    void close() {
        provider.close();
    }

    // Closing the scope is the operation; its binding is intentionally unread.
    @SuppressWarnings("try")
    @Test
    void shouldNestTheHandlersProviderCallUnderTheRequestThatPublishedTheEvent() {
        Map<String, String> headers = new HashMap<>();
        Span request = tracer.spanBuilder()
                .name("http post /github")
                .kind(Span.Kind.SERVER)
                .start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            traceContext.inject(headers);
        } finally {
            request.end();
        }
        String traceId = request.context().traceId();

        RestClient.Builder builder = RestClient.builder().observationRegistry(clientObservations());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(GRAPHQL))
                .andExpect(header("traceparent", containsString(traceId)))
                .andRespond(withSuccess());
        RestClient client = builder.build();

        traceContext.process(
                message(headers), () -> client.post().uri(GRAPHQL).retrieve().toBodilessEntity());

        server.verify();
        SpanData consumer = only(SpanKind.CONSUMER);
        SpanData call = only(SpanKind.CLIENT);
        assertThat(consumer.getTraceId()).isEqualTo(traceId);
        assertThat(consumer.getParentSpanId()).isEqualTo(request.context().spanId());
        assertThat(consumer.getName()).isEqualTo("process github");
        assertThat(call.getTraceId()).isEqualTo(traceId);
        assertThat(call.getParentSpanId()).isEqualTo(consumer.getSpanId());
    }

    @Test
    void shouldStartATraceForAMessageWithoutTraceContext() {
        traceContext.process(message(Map.of()), () -> {});
        traceContext.process(message(Map.of()), () -> {});

        assertThat(finished).hasSize(2).allSatisfy(span -> {
            assertThat(span.getKind()).isEqualTo(SpanKind.CONSUMER);
            assertThat(span.getParentSpanContext().isValid()).isFalse();
        });
        assertThat(finished.get(0).getTraceId()).isNotEqualTo(finished.get(1).getTraceId());
    }

    @Test
    void shouldWriteNothingOutsideASpan() {
        Map<String, String> headers = new HashMap<>();

        traceContext.inject(headers);

        assertThat(headers.isEmpty()).isTrue();
    }

    private EventTraceContext traceContext() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("tracer", tracer);
        beans.addBean("propagator", propagator);
        return new EventTraceContext(beans.getBeanProvider(Tracer.class), beans.getBeanProvider(Propagator.class));
    }

    /** As Spring Boot wires a client: its observations open a client span under the current span. */
    private ObservationRegistry clientObservations() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig()
                .observationHandler(new PropagatingSenderTracingObservationHandler<>(tracer, propagator));
        return registry;
    }

    private static ProviderEvent message(Map<String, String> headers) {
        return new ProviderEvent("github.1.pull_request", headers, new byte[0], Instant.now());
    }

    private SpanData only(SpanKind kind) {
        List<SpanData> spans =
                finished.stream().filter(span -> span.getKind() == kind).toList();
        assertThat(spans).as(kind.name()).hasSize(1);
        return spans.getFirst();
    }
}
