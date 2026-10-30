package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJobExecutor;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.function.ToDoubleFunction;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWorkerRole
public class WorkerConfiguration {

    @Bean
    WorkerCapacityState workerCapacityState(WorkerProperties properties, MeterRegistry meterRegistry) {
        WorkerCapacityState state = new WorkerCapacityState(properties);
        registerCapacityGauges(meterRegistry, state);
        return state;
    }

    private static void registerCapacityGauges(MeterRegistry registry, WorkerCapacityState state) {
        capacityGauge(
                registry,
                state,
                AgentMetrics.WORKER_CAPACITY_TOTAL,
                "review",
                s -> s.reviewMax(),
                "Configured maximum concurrent review jobs");
        capacityGauge(
                registry,
                state,
                AgentMetrics.WORKER_CAPACITY_IN_FLIGHT,
                "review",
                WorkerCapacityState::inFlightReview,
                "Review jobs currently executing");
        capacityGauge(
                registry,
                state,
                AgentMetrics.WORKER_CAPACITY_SPARE,
                "review",
                WorkerCapacityState::spareReview,
                "Spare review slots");
    }

    private static void capacityGauge(
            MeterRegistry registry,
            WorkerCapacityState state,
            String name,
            String type,
            ToDoubleFunction<WorkerCapacityState> value,
            String description) {
        Gauge.builder(name, state, value)
                .description(description)
                .tag("type", type)
                .strongReference(true)
                .register(registry);
    }

    @Bean
    WorkerDrainCoordinator workerDrainCoordinator(
            WorkerProperties properties,
            Optional<ReviewJobExecutor> executor,
            ApplicationEventPublisher events,
            MeterRegistry meterRegistry) {
        return new WorkerDrainCoordinator(properties, executor, events, meterRegistry);
    }
}
