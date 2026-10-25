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
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

// Heartbeats must come from the executing JVM; a lost WSS connection does not mean the worker stopped.
@Component
@ConditionalOnWorkerRole
@ConditionalOnBooleanProperty(RuntimeRole.AGENT_ENABLED_PROPERTY)
@WorkspaceAgnostic("Fleet-wide worker liveness; not workspace-scoped.")
public class WorkerLivenessReporter {

    private static final Logger log = LoggerFactory.getLogger(WorkerLivenessReporter.class);

    private final WorkerRegistryRepository repository;
    private final ReviewJobExecutor executor;
    private final TransactionTemplate transactionTemplate;
    private final Duration interval;
    private final String workerId;
    private final Counter heartbeatFailures;

    /** beat() runs serially: once in start(), then only on the single scheduler thread start() creates after it. */
    private int consecutiveFailures;

    private @Nullable ScheduledExecutorService scheduler;

    // The ledger read and the stops run here, so a slow query or container stop never delays the heartbeat.
    private final Executor stops;

    /**
     * At most one check waits or runs, so a stalled one does not queue another at every heartbeat. A stop that never
     * returns, as with a hung container runtime, holds every later check until it does; the heartbeats continue.
     */
    private final AtomicBoolean checking = new AtomicBoolean();

    @Autowired
    public WorkerLivenessReporter(
            WorkerRegistryRepository repository,
            ReviewJobExecutor executor,
            TransactionTemplate transactionTemplate,
            AgentProperties agentProperties,
            WorkerProperties workerProperties,
            MeterRegistry meterRegistry) {
        this(
                repository,
                executor,
                transactionTemplate,
                agentProperties,
                workerProperties,
                meterRegistry,
                Executors.newSingleThreadExecutor(Thread.ofPlatform()
                        .name("agent-lost-attempt-stop")
                        .daemon(true)
                        .factory()));
    }

    WorkerLivenessReporter(
            WorkerRegistryRepository repository,
            ReviewJobExecutor executor,
            TransactionTemplate transactionTemplate,
            AgentProperties agentProperties,
            WorkerProperties workerProperties,
            MeterRegistry meterRegistry,
            Executor stops) {
        this.stops = stops;
        this.repository = repository;
        this.executor = executor;
        this.transactionTemplate = transactionTemplate;
        this.interval = agentProperties.heartbeatInterval();
        this.workerId = workerProperties.resolvedWorkerId();
        this.heartbeatFailures = Counter.builder(AgentMetrics.WORKER_LIVENESS_HEARTBEAT_FAILURES)
                .description("Failed worker_registry heartbeat writes (a stalled reporter risks false orphaning)")
                .register(meterRegistry);
    }

    // Publish liveness before the first claim, or the sweeper can mistake it for an orphan.
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

    void beat() {
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
        // The ledger, not the control channel, says which jobs this worker still runs.
        if (!checking.compareAndSet(false, true)) return;
        try {
            stops.execute(this::stopLostAttempts);
        } catch (RejectedExecutionException shuttingDown) {
            checking.set(false);
        }
    }

    private void stopLostAttempts() {
        try {
            for (var lost : executor.lostAttemptsToStop()) executor.stopLostAttempt(lost);
        } catch (Exception e) {
            log.warn(
                    "Could not check which local jobs this worker still owns: workerId={}, error={}",
                    workerId,
                    e.getClass().getSimpleName());
        } finally {
            checking.set(false);
        }
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (stops instanceof ExecutorService owned) owned.shutdownNow();
        // Deliberately do NOT delete the registry row: drain is best-effort, so a job may still be
        // RUNNING, and deleting the row would orphan it into an immediate re-run by a sibling. Stopping
        // the heartbeat instead lets the lease expire, which recovers only genuinely-unfinished jobs.
        // ReviewJobZombieSweeper purges the row afterwards.
    }
}
