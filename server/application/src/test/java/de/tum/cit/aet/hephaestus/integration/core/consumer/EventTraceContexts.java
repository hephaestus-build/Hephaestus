package de.tum.cit.aet.hephaestus.integration.core.consumer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** The tracing and the message meters of a consumer, for tests that read neither. */
public final class EventTraceContexts {

    private EventTraceContexts() {}

    public static EventTraceContext none() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        return new EventTraceContext(beans.getBeanProvider(Tracer.class), beans.getBeanProvider(Propagator.class));
    }

    public static IntegrationMessageMeters messageMeters() {
        return new IntegrationMessageMeters(new SimpleMeterRegistry(), Clock.systemUTC());
    }
}
