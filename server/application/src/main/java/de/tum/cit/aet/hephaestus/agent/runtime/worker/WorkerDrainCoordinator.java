package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import de.tum.cit.aet.hephaestus.agent.job.AgentJobCancellationReason;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobExecutor;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.CapacityReport;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.Heartbeat;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.WorkerControlFrame;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;

/**
 * Graceful shutdown for the worker. Liveness stays {@code CORRECT} — kubelet must not kill the
 * pod early; only readiness flips to {@code REFUSING_TRAFFIC}.
 *
 * <p>Sequence: readiness flip → final {@code Heartbeat{draining}} + capacity report with
 * {@code spare=0} → stop accepting new jobs → await in-flight (or cancel immediately when
 * {@code timeout=0}).
 *
 * <p>The phase is one above {@link WebServerApplicationContext#GRACEFUL_SHUTDOWN_PHASE}, so the drain
 * stops before the web server's graceful shutdown starts. A review that is drained still sends its
 * model requests through the sandbox gateway, a connector of that same server, and graceful shutdown
 * closes every connector.
 */
public class WorkerDrainCoordinator implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerDrainCoordinator.class);

    static final int PHASE = WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE + 1;

    private final WorkerControlClient client;
    private final WorkerCapacityState state;
    private final WorkerProperties properties;
    private final Optional<AgentJobExecutor> executor;
    private final Optional<WorkerMentorSessions> mentorSessions;
    private final ApplicationEventPublisher events;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean draining = new AtomicBoolean(false);

    public WorkerDrainCoordinator(
            WorkerControlClient client,
            WorkerCapacityState state,
            WorkerProperties properties,
            Optional<AgentJobExecutor> executor,
            Optional<WorkerMentorSessions> mentorSessions,
            ApplicationEventPublisher events,
            MeterRegistry meterRegistry) {
        this.client = client;
        this.state = state;
        this.properties = properties;
        this.executor = executor;
        this.mentorSessions = mentorSessions;
        this.events = events;
        Gauge.builder(AgentMetrics.WORKER_DRAIN_ACTIVE, draining, b -> b.get() ? 1.0 : 0.0)
                .description("1 while the worker is draining, 0 otherwise")
                .register(meterRegistry);
    }

    @Override
    public void start() {
        running.set(true);
    }

    @Override
    public void stop() {
        stop(() -> {});
    }

    @Override
    public void stop(Runnable callback) {
        try {
            if (running.compareAndSet(true, false)) {
                draining.set(true);
                Duration timeout = properties.drain().timeout();
                log.info("Worker drain starting (timeout={})…", timeout);

                AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);
                safeSend(new Heartbeat(true));
                CapacityReport snap = state.snapshot();
                safeSend(new CapacityReport(
                        snap.reviewMax(), snap.mentorMax(), snap.inFlightReview(), snap.inFlightMentor(), 0, 0));

                mentorSessions.ifPresent(WorkerMentorSessions::stop);
                boolean complete = executor.map(e -> drainExecutor(e, timeout)).orElse(true);
                if (complete) {
                    log.info("Worker drain finished: owned job states settled and container stop calls returned.");
                } else {
                    log.warn("Worker drain incomplete: a job state was not settled, or a container stop failed or"
                            + " did not return.");
                }
            }
        } finally {
            callback.run();
        }
    }

    /** Drains the executor; false if a job was left unsettled or a container stop did not return. */
    private boolean drainExecutor(AgentJobExecutor exec, Duration timeout) {
        exec.stopAcceptingNewJobs();
        if (timeout.isZero()) {
            log.info("Drain mode IMMEDIATE — cancelling in-flight jobs without waiting.");
            return exec.cancelInFlight(AgentJobCancellationReason.DRAIN_IMMEDIATE);
        }
        boolean clean = exec.awaitInFlight(timeout);
        if (clean) {
            log.info("Drain awaited cleanly; no jobs left to cancel.");
            return true;
        }
        log.warn("Drain budget exhausted after {}; cancelling in-flight jobs.", timeout);
        return exec.cancelInFlight(AgentJobCancellationReason.DRAIN_GRACEFUL);
    }

    private void safeSend(WorkerControlFrame frame) {
        try {
            client.send(frame);
        } catch (Exception e) {
            log.warn(
                    "Drain-time send failed for {}: {}",
                    frame.getClass().getSimpleName(),
                    e.getClass().getSimpleName());
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    public boolean isDraining() {
        return draining.get();
    }
}
