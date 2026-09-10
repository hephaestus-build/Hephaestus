package de.tum.cit.aet.hephaestus.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Echoes the request's trace ID as {@code X-Request-Id} and opens a propagated span when Boot's own
 * observation filter has not already done so.
 *
 * <p>The {@link Tracer} and {@link Propagator} are resolved via {@link ObjectProvider} because they
 * come from Spring Boot's tracing autoconfig (registered after user config) —
 * {@code @ConditionalOnBean} on a component-scanned bean evaluates before the autoconfig beans exist
 * and would silently drop this filter (same reasoning as the {@code JwtDecoder} note in
 * {@code SecurityConfig}). With tracing disabled the filter degrades to a pass-through.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Request-Id";

    private final @Nullable Tracer tracer;
    private final @Nullable Propagator propagator;

    public RequestCorrelationFilter(
            ObjectProvider<Tracer> tracerProvider, ObjectProvider<Propagator> propagatorProvider) {
        this.tracer = tracerProvider.getIfAvailable();
        this.propagator = propagatorProvider.getIfAvailable();
    }

    // Closing the scope is the operation; its binding is intentionally unread.
    @SuppressWarnings("try")
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (tracer == null || propagator == null) {
            chain.doFilter(request, response);
            return;
        }
        Span current = tracer.currentSpan();
        if (current != null) {
            response.setHeader(HEADER_NAME, current.context().traceId());
            chain.doFilter(request, response);
            return;
        }

        Span span = propagator
                .extract(request, HttpServletRequest::getHeader)
                .name("http.request")
                .kind(Span.Kind.SERVER)
                .start();
        response.setHeader(HEADER_NAME, span.context().traceId());

        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException error) {
            span.error(error);
            throw error;
        } finally {
            span.end();
        }
    }
}
