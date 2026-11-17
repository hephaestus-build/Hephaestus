package de.tum.cit.aet.hephaestus.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationFilter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerHttpObservationDocumentation;

/**
 * Exported spans carry technical data only: route templates, status, timing and identifiers, never what a person
 * wrote or an address that can name a person. An exported span leaves the server for the trace store.
 *
 * <p>Spring's request observations add the raw path of a request, and the full URL of a provider call, as
 * {@code http.url}. A path can name a person's login or a workspace named after one. The template stays in the
 * low-cardinality {@code uri} key, which the request metrics read too, so a span still names its route.
 *
 * <p>A span that records an exception keeps its type. Its message and stack trace can quote a request's data, so the
 * exporter leaves them out of the exception event and of the error status; the server's log keeps them.
 */
@Configuration
public class TechnicalSpansConfiguration {

    // Server and client observations name the key alike, in Spring MVC, RestClient and WebClient. The url.* keys are
    // the names of Spring's OpenTelemetry conventions, should the application switch to them.
    private static final List<String> ADDRESSES = List.of(
            ServerHttpObservationDocumentation.HighCardinalityKeyNames.HTTP_URL.asString(),
            "url.full",
            "url.path",
            "url.query");

    // A client call that passes its whole address as the template carries the query in its route too.
    private static final String ROUTE = "uri";

    // The OpenTelemetry semantic convention's name; the semconv artifact is not on the classpath.
    static final AttributeKey<String> EXCEPTION_TYPE = AttributeKey.stringKey("exception.type");
    private static final String EXCEPTION = "exception";

    @Bean
    ObservationFilter routeTemplateSpans() {
        return context -> {
            ADDRESSES.forEach(context::removeHighCardinalityKeyValue);
            var route = context.getLowCardinalityKeyValue(ROUTE);
            if (route != null && route.getValue().indexOf('?') >= 0) {
                context.addLowCardinalityKeyValue(KeyValue.of(
                        ROUTE, route.getValue().substring(0, route.getValue().indexOf('?'))));
            }
            return context;
        };
    }

    @Bean
    static BeanPostProcessor technicalSpanExport() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof SpanExporter exporter ? new TechnicalSpanExporter(exporter) : bean;
            }
        };
    }

    record TechnicalSpanExporter(SpanExporter delegate) implements SpanExporter {
        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            return delegate.export(spans.stream()
                    .map(TechnicalSpanData::new)
                    .map(SpanData.class::cast)
                    .toList());
        }

        @Override
        public CompletableResultCode flush() {
            return delegate.flush();
        }

        @Override
        public CompletableResultCode shutdown() {
            return delegate.shutdown();
        }
    }

    static final class TechnicalSpanData extends DelegatingSpanData {
        TechnicalSpanData(SpanData span) {
            super(span);
        }

        @Override
        public StatusData getStatus() {
            var status = super.getStatus();
            return status.getStatusCode() == StatusCode.ERROR ? StatusData.error() : status;
        }

        @Override
        public List<EventData> getEvents() {
            return super.getEvents().stream().map(TechnicalSpanData::typeOnly).toList();
        }

        private static EventData typeOnly(EventData event) {
            if (!EXCEPTION.equals(event.getName())) return event;
            var type = event.getAttributes().get(EXCEPTION_TYPE);
            return EventData.create(
                    event.getEpochNanos(),
                    event.getName(),
                    type == null ? Attributes.empty() : Attributes.of(EXCEPTION_TYPE, type));
        }
    }
}
