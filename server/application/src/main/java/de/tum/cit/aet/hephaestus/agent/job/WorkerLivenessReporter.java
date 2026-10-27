package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

// Heartbeats must come from the executing JVM; a lost WSS connection does not mean the worker stopped. Every worker
// beats, also one that runs no practice reviews: the Kubernetes sandboxes of a worker that no longer beats are removed.
// Each beat also renews the lease of every review attempt that this worker runs.
@Component
@ConditionalOnWorkerRole
@WorkspaceAgnostic("Fleet-wide worker liveness; not workspace-scoped.")
public class WorkerLivenessReporter {

    private static final Logger log = LoggerFactory.getLogger(WorkerLivenessReporter.class);

    private final WorkerRegistryRepository repository;
    private final ReviewJobRepository jobs;
    private final TransactionTemplate transactionTemplate;
    private final Duration interval;
    private final String workerId;
    private final Counter heartbeatFailures;

    /** beat() runs serially: once in start(), then only on the single scheduler thread start() creates after it. */
    private int consecutiveFailures;

    private @Nullable ScheduledExecutorService scheduler;

    public WorkerLivenessReporter(
            WorkerRegistryRepository repository,
            ReviewJobRepository jobs,
            TransactionTemplate transactionTemplate,
            AgentProperties agentProperties,
            WorkerProperties workerProperties,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.jobs = jobs;
        this.transactionTemplate = transactionTemplate;
        this.interval = agentProperties.heartbeatInterval();
        this.workerId = workerProperties.resolvedWorkerId();
        this.heartbeatFailures = Counter.builder(AgentMetrics.WORKER_LIVENESS_HEARTBEAT_FAILURES)
                .description(
                        "Failed worker_registry heartbeat writes (a stalled reporter risks the requeue of its reviews)")
                .register(meterRegistry);
    }

    // Publish liveness before the first claim: other workers keep a worker's Heph sandboxes only while it beats.
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

    /** One heartbeat: this worker's registry row, and the leases of the review attempts that it runs. */
    void beat() {
        try {
            transactionTemplate.executeWithoutResult(s -> {
                repository.heartbeat(workerId);
                jobs.renewLeases(workerId, AgentProperties.WORKER_LEASE_TTL.toSeconds());
            });
            if (consecutiveFailures > 0) {
                log.info(
                        "Worker liveness heartbeat recovered after {} failure(s): workerId={}",
                        consecutiveFailures,
                        workerId);
                consecutiveFailures = 0;
            }
        } catch (Exception e) {
            // WARN, never DEBUG: a worker that cannot heartbeat loses its leases, and its jobs get
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
        // Deliberately do NOT end the leases: drain is best-effort, so a job may still be RUNNING. Stopping
        // the heartbeat lets each lease expire, which recovers only genuinely-unfinished jobs.
        // ReviewJobZombieSweeper purges the registry row afterwards.
    }
}
