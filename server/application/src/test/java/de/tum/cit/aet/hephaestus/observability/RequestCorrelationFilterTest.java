package de.tum.cit.aet.hephaestus.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@Tag("unit")
class RequestCorrelationFilterTest {

    @Test
    void shouldEchoExtractedTraceIdOnResponse() throws Exception {
        Tracer tracer = mock(Tracer.class);
        Propagator propagator = mock(Propagator.class);
        Span.Builder builder = mock(Span.Builder.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(propagator.extract(any(), any())).thenReturn(builder);
        when(builder.name("http.request")).thenReturn(builder);
        when(builder.kind(Span.Kind.SERVER)).thenReturn(builder);
        when(builder.start()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(mock(Tracer.SpanInScope.class));
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("0123456789abcdef0123456789abcdef");

        MockHttpServletResponse response = new MockHttpServletResponse();
        new RequestCorrelationFilter(provider(tracer), provider(propagator))
                .doFilter(new MockHttpServletRequest(), response, new MockFilterChain());

        assertThat(response.getHeader(RequestCorrelationFilter.HEADER_NAME))
                .isEqualTo("0123456789abcdef0123456789abcdef");
    }

    @Test
    void shouldPassThroughWithoutHeaderWhenTracingIsDisabled() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new RequestCorrelationFilter(provider((Tracer) null), provider((Propagator) null))
                .doFilter(new MockHttpServletRequest(), response, chain);

        assertThat(response.getHeader(RequestCorrelationFilter.HEADER_NAME)).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    private static <T> ObjectProvider<T> provider(@Nullable T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                if (instance == null) throw new IllegalStateException("no instance");
                return instance;
            }

            @Override
            public @Nullable T getIfAvailable() {
                return instance;
            }
        };
    }
}
