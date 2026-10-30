package de.tum.cit.aet.hephaestus.agent.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentPhaseObservations.Phase;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentPhaseObservations.Workload;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AgentPhaseObservationsTest extends BaseUnitTest {
    @Test
    void shouldRecordElapsedAndRemoteSpansWithTheSameBoundedTags() {
        var clock = new MockClock();
        var meters = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        var phases = new AgentPhaseObservations(registry, meters);
        var observation = phases.start(Workload.CONVERSATION, Phase.MODEL_CALL);
        clock.add(Duration.ofMillis(250));
        observation.completed();
        observation.close();
        phases.record(Workload.REVIEW, Phase.TOOL_CALL, Duration.ofMillis(400));

        var model = meters.get(AgentMetrics.AGENT_PHASE_DURATION)
                .tags("workload", "conversation", "phase", "model_call")
                .timer();
        assertThat(model.count()).isEqualTo(1);
        assertThat(model.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(250);
        assertThat(model.getId().getTags())
                .extracting(tag -> tag.getKey())
                .containsOnly("acquisition", "error", "outcome", "phase", "workload");
        var tool = meters.get(AgentMetrics.AGENT_PHASE_REPORTED_DURATION)
                .tags("workload", "review", "phase", "tool_call")
                .timer();
        assertThat(tool.count()).isEqualTo(1);
        assertThat(tool.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(400);
    }

    @Test
    void shouldExportBothTimerFamiliesWhenObservationsHaveCommonTags() {
        var meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            var registry = ObservationRegistry.create();
            registry.observationConfig()
                    .observationFilter(context -> context.addLowCardinalityKeyValue(KeyValue.of("deployment", "test")))
                    .observationHandler(new DefaultMeterObservationHandler(meters));
            var phases = new AgentPhaseObservations(registry, meters);
            try (var call = phases.start(Workload.CONVERSATION, Phase.MODEL_CALL)) {
                call.completed();
            }
            phases.record(Workload.REVIEW, Phase.TOOL_CALL, Duration.ofMillis(400));

            assertThat(meters.scrape())
                    .contains("agent_phase_duration_seconds_count{")
                    .contains("agent_phase_reported_duration_seconds_count{")
                    .contains("deployment=\"test\"");
        } finally {
            meters.close();
        }
    }

    @Test
    void shouldSeparateFailedAndCancelledSpansWithoutPrivateExceptionTags() {
        var meters = new SimpleMeterRegistry();
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        var phases = new AgentPhaseObservations(registry, meters);
        try (var failed = phases.start(Workload.CONVERSATION, Phase.SANDBOX_START)) {
            failed.acquisition(AgentPhaseObservations.Acquisition.COLD);
        }
        try (var cancelled = phases.start(Workload.CONVERSATION, Phase.MODEL_CALL)) {
            cancelled.cancelled();
        }
        assertThat(meters.get(AgentMetrics.AGENT_PHASE_DURATION)
                        .tags("phase", "sandbox_start", "outcome", "failed", "error", "PhaseFailure")
                        .timer()
                        .count())
                .isEqualTo(1);
        assertThat(meters.get(AgentMetrics.AGENT_PHASE_DURATION)
                        .tags("phase", "model_call", "outcome", "cancelled", "error", "none")
                        .timer()
                        .count())
                .isEqualTo(1);
    }
}
