package de.tum.cit.aet.hephaestus.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.observation.ClientHttpObservationDocumentation;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerHttpObservationDocumentation;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** An exported span names a route by its template and an error by its type, never by what a person typed. */
class TechnicalSpansTest extends BaseUnitTest {

    private final List<SpanData> exported = new CopyOnWriteArrayList<>();
    private final SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(new TechnicalSpans.TechnicalSpanExporter(new SpanExporter() {
                @Override
                public CompletableResultCode export(Collection<SpanData> spans) {
                    exported.addAll(spans);
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
            })))
            .build();
    private final io.opentelemetry.api.trace.Tracer otelTracer = provider.get("test");
    private final OtelCurrentTraceContext current = new OtelCurrentTraceContext();
    private final Tracer tracer =
            new OtelTracer(otelTracer, current, event -> {}, new OtelBaggageManager(current, List.of(), List.of()));
    private final Propagator propagator =
            new OtelPropagator(ContextPropagators.create(W3CTraceContextPropagator.getInstance()), otelTracer);
    private final ObservationRegistry registry = observations();

    @AfterEach
    void close() {
        provider.close();
    }

    @Test
    void shouldExportTheRouteTemplateOfARequestWhosePathNamesALogin() {
        var request = new MockHttpServletRequest("GET", "/api/workspaces/octocat/users/octocat");
        request.setQueryString("tab=octocat");
        var context = new ServerRequestObservationContext(request, new MockHttpServletResponse());
        context.setPathPattern("/api/workspaces/{workspaceSlug}/users/{login}");

        ServerHttpObservationDocumentation.HTTP_SERVLET_SERVER_REQUESTS
                .observation(null, new DefaultServerRequestObservationConvention(), () -> context, registry)
                .start()
                .stop();

        var span = exported.getFirst();
        assertThat(span.getAttributes().asMap().values())
                .noneMatch(value -> value.toString().contains("octocat"));
        assertThat(span.getName()).doesNotContain("octocat");
        assertThat(span.getAttributes().asMap())
                .anySatisfy(
                        (key, value) -> assertThat(value).isEqualTo("/api/workspaces/{workspaceSlug}/users/{login}"));
    }

    @Test
    void shouldExportTheTemplateOfAProviderCallWhoseAddressNamesALogin() {
        var request =
                new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.github.com/users/octocat/repos"));
        var context = new ClientRequestObservationContext(request);
        context.setUriTemplate("/users/{login}/repos");

        ClientHttpObservationDocumentation.HTTP_CLIENT_EXCHANGES
                .observation(null, new DefaultClientRequestObservationConvention(), () -> context, registry)
                .start()
                .stop();

        var span = exported.getFirst();
        assertThat(span.getAttributes().asMap().values())
                .noneMatch(value -> value.toString().contains("octocat"));
        assertThat(span.getAttributes().asMap()).containsValue("/users/{login}/repos");
    }

    @Test
    void shouldExportAnErrorByItsTypeWithoutItsMessageOrStack() {
        var request = new MockHttpServletRequest("GET", "/api/workspaces/octocat");
        var context = new ServerRequestObservationContext(request, new MockHttpServletResponse());
        context.setPathPattern("/api/workspaces/{workspaceSlug}");

        var observation = ServerHttpObservationDocumentation.HTTP_SERVLET_SERVER_REQUESTS
                .observation(null, new DefaultServerRequestObservationConvention(), () -> context, registry)
                .start();
        observation.error(new IllegalStateException("No workspace octocat for octocat@example.com"));
        observation.stop();

        var span = exported.getFirst();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getStatus().getDescription()).doesNotContain("octocat");
        assertThat(span.getEvents()).isNotEmpty().allSatisfy(event -> {
            assertThat(event.getAttributes().get(TechnicalSpans.EXCEPTION_TYPE))
                    .isEqualTo(IllegalStateException.class.getName());
            assertThat(event.getAttributes().asMap().values())
                    .noneMatch(value -> value.toString().contains("octocat"));
        });
    }

    @Test
    void shouldWrapTheExporterThatTheApplicationDefines() {
        new ApplicationContextRunner()
                .withUserConfiguration(TechnicalSpans.class)
                .withBean(SpanExporter.class, () -> mock(SpanExporter.class))
                .run(context -> assertThat(context.getBean(SpanExporter.class))
                        .isInstanceOf(TechnicalSpans.TechnicalSpanExporter.class));
    }

    /** As Spring Boot wires them: the application's filters first, then the tracing handlers. */
    private ObservationRegistry observations() {
        var observations = ObservationRegistry.create();
        observations
                .observationConfig()
                .observationFilter(new TechnicalSpans().routeTemplateSpans())
                .observationHandler(new PropagatingReceiverTracingObservationHandler<>(tracer, propagator))
                .observationHandler(new PropagatingSenderTracingObservationHandler<>(tracer, propagator));
        return observations;
    }
}
