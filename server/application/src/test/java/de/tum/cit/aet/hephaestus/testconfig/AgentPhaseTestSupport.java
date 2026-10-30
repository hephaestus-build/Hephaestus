package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentPhaseObservations;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

public final class AgentPhaseTestSupport {
    private AgentPhaseTestSupport() {}

    public static AgentPhaseObservations noopPhaseObservations() {
        return new AgentPhaseObservations(ObservationRegistry.NOOP, new SimpleMeterRegistry());
    }

    public static AgentPhaseObservations observedPhaseObservations(MeterRegistry meters) {
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        return new AgentPhaseObservations(registry, meters);
    }
}
