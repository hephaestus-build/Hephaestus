package de.tum.cit.aet.hephaestus.integration.core.consumer;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** {@link NatsTraceContext} for tests that run without tracing. */
public final class NatsTraceContexts {

    private NatsTraceContexts() {}

    public static NatsTraceContext none() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        return new NatsTraceContext(beans.getBeanProvider(Tracer.class), beans.getBeanProvider(Propagator.class));
    }
}
