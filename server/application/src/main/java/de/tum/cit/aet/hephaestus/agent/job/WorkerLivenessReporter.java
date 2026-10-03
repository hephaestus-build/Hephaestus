package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Self-reports this worker's liveness into {@code worker_registry} on a timer.
 *
 * <p>The heartbeat must come from the JVM that actually executes jobs, never from the WSS control
 * channel: a worker can lose WSS while still running jobs, and orphaning those would double-execute
 * them. Only "this executor is gone" is a safe trigger for {@link AgentJobZombieSweeper} to hand a
 * RUNNING job to a sibling.
 *
 * <p>Manually scheduled rather than {@code @Scheduled}, because {@code @EnableScheduling} is server-role
 * only and this bean runs on worker pods.
 */
@Component
@ConditionalOnWorkerRole
@ConditionalOnBooleanProperty(RuntimeRole.AGENT_ENABLED_PROPERTY)
@WorkspaceAgnostic("Fleet-wide worker liveness; not workspace-scoped.")
public class WorkerLivenessReporter {

    private static final Logger log = LoggerFactory.getLogger(WorkerLivenessReporter.class);

    private final WorkerRegistryRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final Duration interval;
    private final String workerId;
    private final Counter heartbeatFailures;

    /** beat() runs serially: once in start(), then only on the single scheduler thread start() creates after it. */
    private int consecutiveFailures;

    private @Nullable ScheduledExecutorService scheduler;

    public WorkerLivenessReporter(
            WorkerRegistryRepository repository,
            TransactionTemplate transactionTemplate,
            AgentProperties agentProperties,
            WorkerProperties workerProperties,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.interval = agentProperties.heartbeatInterval();
        this.workerId = workerProperties.resolvedWorkerId();
        this.heartbeatFailures = Counter.builder(AgentMetrics.WORKER_LIVENESS_HEARTBEAT_FAILURES)
                .description("Failed worker_registry heartbeat writes (a stalled reporter risks false orphaning)")
                .register(meterRegistry);
    }

    /** Must stay ordered ahead of {@code AgentJobExecutor.start()}, or its first claim looks orphaned. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void start() {
        beat(); // synchronous, so the registry row exists before any job can be claimed
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "worker-liveness");
            t.setDaemon(true);
            return t;
        });
        // Periodic; stop() ends it with shutdownNow().
        var unused =
                scheduler.scheduleAtFixedRate(this::beat, interval.toSeconds(), interval.toSeconds(), TimeUnit.SECONDS);
        log.info("Worker liveness reporter started: workerId={}, interval={}", workerId, interval);
    }

    private void beat() {
        try {
            transactionTemplate.executeWithoutResult(s -> repository.heartbeat(workerId));
            if (consecutiveFailures > 0) {
                log.info(
                        "Worker liveness heartbeat recovered after {} failure(s): workerId={}",
                        consecutiveFailures,
                        workerId);
                consecutiveFailures = 0;
            }
        } catch (Exception e) {
            // WARN, never DEBUG: a worker that cannot heartbeat gets falsely orphaned and its jobs
            // double-executed.
            heartbeatFailures.increment();
            consecutiveFailures++;
            log.warn(
                    "Worker liveness heartbeat failed (consecutive={}): workerId={}, error={}",
                    consecutiveFailures,
                    workerId,
                    e.getClass().getSimpleName());
        }
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        // Deliberately do NOT delete the registry row: drain is best-effort, so a job may still be
        // RUNNING, and deleting the row would orphan it into an immediate re-run by a sibling. Stopping
        // the heartbeat instead lets the lease expire, which recovers only genuinely-unfinished jobs.
        // AgentJobZombieSweeper purges the row afterwards.
    }
}
