package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import de.tum.cit.aet.hephaestus.agent.job.AgentJobExecutor;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.FrameCodec;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.ToDoubleFunction;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires the worker-runtime substrate beans. Gated by {@link RuntimeRole#WORKER_PROPERTY}
 * (matchIfMissing=true) AND by a non-empty {@code hephaestus.worker.control.endpoint}: the
 * substrate has exactly one runtime path — a WSS-connected worker pod. Monolith dev mode
 * leaves the gate set but never points at an endpoint, so no substrate beans wire; the in-process
 * executor claims jobs from the {@code agent_job} table regardless.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = RuntimeRole.WORKER_PROPERTY, havingValue = "true", matchIfMissing = true)
@ConditionalOnExpression("'${hephaestus.worker.control.endpoint:}'.length() > 0")
public class WorkerConfiguration {

    /**
     * Single-thread daemon scheduler shared by the capacity reporter. Kept off Spring's default
     * {@code TaskScheduler} so worker telemetry can't be starved by application {@code @Scheduled}
     * methods or accidentally inherit their thread-pool tuning.
     */
    @Bean(name = "workerScheduler", destroyMethod = "shutdownNow")
    ScheduledExecutorService workerScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "worker-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

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
                AgentMetrics.WORKER_CAPACITY_TOTAL,
                "mentor",
                s -> s.mentorMax(),
                "Configured maximum concurrent mentor sessions");
        capacityGauge(
                registry,
                state,
                AgentMetrics.WORKER_CAPACITY_IN_FLIGHT,
                "review",
                s -> s.snapshot().inFlightReview(),
                "Review jobs currently executing");
        capacityGauge(
                registry,
                state,
                AgentMetrics.WORKER_CAPACITY_IN_FLIGHT,
                "mentor",
                s -> s.snapshot().inFlightMentor(),
                "Mentor sessions currently open");
        capacityGauge(
                registry,
                state,
                AgentMetrics.WORKER_CAPACITY_SPARE,
                "review",
                s -> s.snapshot().spareReview(),
                "Spare review slots");
        capacityGauge(
                registry,
                state,
                AgentMetrics.WORKER_CAPACITY_SPARE,
                "mentor",
                s -> s.snapshot().spareMentor(),
                "Spare mentor slots");
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
    WorkerControlClient workerControlClient(
            WorkerProperties properties,
            FrameCodec frameCodec,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        WorkerControlClient client = new WorkerControlClient(properties, frameCodec, objectMapper, meterRegistry);
        Gauge.builder(AgentMetrics.WORKER_CONTROL_CHANNEL_CONNECTED, client, c -> c.isConnected() ? 1.0 : 0.0)
                .description("1 when the worker control channel is connected, 0 otherwise")
                .strongReference(true)
                .register(meterRegistry);
        return client;
    }

    @Bean
    @ConditionalOnMissingBean(FrameCodec.class)
    FrameCodec frameCodec(ObjectMapper objectMapper) {
        return new FrameCodec(objectMapper);
    }

    @Bean
    WorkerCapacityReporter workerCapacityReporter(
            WorkerCapacityState state,
            WorkerControlClient client,
            WorkerProperties properties,
            ScheduledExecutorService workerScheduler,
            MeterRegistry meterRegistry) {
        return new WorkerCapacityReporter(state, client, properties, workerScheduler, meterRegistry);
    }

    @Bean
    WorkerControlChannelHealthIndicator workerControlChannelHealthIndicator(
            WorkerControlClient client, WorkerProperties properties) {
        return new WorkerControlChannelHealthIndicator(client, properties);
    }

    /**
     * Wire hub-originated {@code CancelJob} frames to the executor's local-cancel path.
     * Done after singletons are instantiated so the optional executor (only present when
     * {@code hephaestus.agent.enabled}) is resolved without creating a hard dependency cycle.
     */
    @Bean
    SmartInitializingSingleton workerCancelHandlerWiring(
            WorkerControlClient client, Optional<AgentJobExecutor> executor) {
        return () -> executor.ifPresent(e -> client.setCancelHandler(e::cancelLocalJob));
    }

    @Bean
    WorkerDrainCoordinator workerDrainCoordinator(
            WorkerControlClient client,
            WorkerCapacityState state,
            WorkerProperties properties,
            Optional<AgentJobExecutor> executor,
            ApplicationEventPublisher events,
            MeterRegistry meterRegistry) {
        return new WorkerDrainCoordinator(client, state, properties, executor, events, meterRegistry);
    }
}
