package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.integration.core.handler.ProviderEvent;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Carries a trace across the inbox. The receiver writes the current trace context into the stored headers
 * ({@code traceparent}), and the consumer continues it in a consumer span around the handler. The provider calls
 * that a handler makes then belong to the trace of the webhook request that stored the event, instead of each
 * starting a trace of its own.
 *
 * <p>The {@link Tracer} and {@link Propagator} come from Spring Boot's tracing autoconfiguration, so they are
 * resolved through {@link ObjectProvider}, as in {@code RequestCorrelationFilter}. Without them both operations
 * pass through.
 */
@Component
public class EventTraceContext {

    private final @Nullable Tracer tracer;
    private final @Nullable Propagator propagator;

    public EventTraceContext(ObjectProvider<Tracer> tracerProvider, ObjectProvider<Propagator> propagatorProvider) {
        this.tracer = tracerProvider.getIfAvailable();
        this.propagator = propagatorProvider.getIfAvailable();
    }

    /** Writes the trace context of the current span into {@code headers}. Without a current span, writes nothing. */
    public void inject(Map<String, String> headers) {
        if (tracer == null || propagator == null) {
            return;
        }
        Span current = tracer.currentSpan();
        if (current != null) {
            propagator.inject(current.context(), headers, (carrier, key, value) -> {
                if (carrier != null) {
                    carrier.put(key, value);
                }
            });
        }
    }

    /**
     * Runs {@code handler} in a consumer span that continues the receiver's trace. An event without trace context
     * starts a new trace.
     */
    // Closing the scope is the operation; its binding is intentionally unread.
    @SuppressWarnings("try")
    public void process(ProviderEvent event, Runnable handler) {
        if (tracer == null || propagator == null) {
            handler.run();
            return;
        }
        Span span = propagator
                .extract(event.headers(), Map::get)
                .name("process " + IntegrationPoisonHandler.kindTag(event.subject()))
                .kind(Span.Kind.CONSUMER)
                .tag("messaging.system", "postgresql")
                .tag("messaging.operation.type", "process")
                .tag("messaging.destination.name", event.subject())
                .start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            handler.run();
        } catch (RuntimeException error) {
            span.error(error);
            throw error;
        } finally {
            span.end();
        }
    }
}
