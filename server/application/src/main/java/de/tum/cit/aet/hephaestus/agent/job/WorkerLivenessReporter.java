package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

// Heartbeats must come from the executing JVM. Every worker beats, also one that runs no practice reviews: the
// Kubernetes sandboxes of a worker that no longer beats are removed. Each beat also renews the lease of each review
// attempt that runs here, by the attempt's own lease, and stops each local attempt whose lease is gone.
@Component
@ConditionalOnWorkerRole
@WorkspaceAgnostic("Fleet-wide worker liveness; not workspace-scoped.")
public class WorkerLivenessReporter {

    private static final Logger log = LoggerFactory.getLogger(WorkerLivenessReporter.class);

    private final WorkerRegistryRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final Optional<ReviewJobExecutor> executor;
    private final Duration interval;
    private final String workerId;
    private final Counter heartbeatFailures;

    /** beat() runs serially: once in start(), then only on the single scheduler thread start() creates after it. */
    private int consecutiveFailures;

    private @Nullable ScheduledExecutorService scheduler;

    private final ExecutorService stopper = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ended-attempt-stopper");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean stopping = new AtomicBoolean();

    public WorkerLivenessReporter(
            WorkerRegistryRepository repository,
            TransactionTemplate transactionTemplate,
            Optional<ReviewJobExecutor> executor,
            AgentProperties agentProperties,
            WorkerProperties workerProperties,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.executor = executor;
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

    /**
     * One heartbeat: this worker's registry row, and the lease of each review attempt that runs here. A cancel, a
     * requeue or a reap ends an attempt on its row alone; the proxy refuses its calls at once, and this beat then stops
     * its sandbox. A beat that fails stops nothing: it does not know which leases are gone.
     */
    void beat() {
        Map<UUID, ReviewJobExecutor.LocalAttempt> ended = Map.of();
        try {
            ended = Objects.requireNonNull(transactionTemplate.execute(s -> {
                repository.heartbeat(workerId);
                return executor.map(ReviewJobExecutor::renewLeases).orElse(Map.of());
            }));
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
        if (ended.isEmpty()) return;
        Map<UUID, ReviewJobExecutor.LocalAttempt> lost = ended;
        executor.ifPresent(running -> stopEndedAttempts(running, lost));
    }

    /**
     * Stops the sandboxes of attempts that ended elsewhere, on a thread of its own: a stop waits on the sandbox backend,
     * and a slow backend must neither delay the next lease renewal nor count as a failed heartbeat. A sweep still
     * running skips this beat's.
     */
    private void stopEndedAttempts(ReviewJobExecutor executor, Map<UUID, ReviewJobExecutor.LocalAttempt> ended) {
        if (!stopping.compareAndSet(false, true)) return;
        stopper.execute(() -> {
            try {
                executor.stopEndedAttempts(ended);
            } catch (RuntimeException e) {
                log.warn(
                        "Stopping the sandboxes of ended attempts failed: workerId={}, error={}",
                        workerId,
                        e.getClass().getSimpleName());
            } finally {
                stopping.set(false);
            }
        });
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        stopper.shutdownNow();
        // Deliberately do NOT end the leases: drain is best-effort, so a job may still be RUNNING. Stopping
        // the heartbeat lets each lease expire, which recovers only genuinely-unfinished jobs.
        // ReviewJobZombieSweeper purges the registry row afterwards.
    }
}
