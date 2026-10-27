package de.tum.cit.aet.hephaestus.agent.sandbox;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxBackend;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Removes the sandboxes that no attempt or worker uses any more, at the worker's start and then periodically, on every
 * runtime ({@link SandboxBackend#list}):
 *
 * <ul>
 *   <li>A practice review's sandbox goes once its job is neither queued nor running, unless it is younger than the
 *       grace: the job set is read before the sandboxes, so a sandbox started in between is not in it.
 *   <li>A practice review's sandbox also goes once its job holds a later attempt: a requeue gave the work to a new
 *       attempt, and the old one's output is skipped. No grace applies, since an attempt starts its sandbox only after
 *       the job row holds that attempt, so an attempt below the one read is never the current one.
 *   <li>A Heph host goes once the worker that launched it is positively gone.
 * </ul>
 *
 * <p>A sweep that cannot read the jobs or the sandboxes removes nothing: a sweep blind to either would take every live
 * sandbox for abandoned. A removal that fails is tried again by the next sweep.
 */
@WorkspaceAgnostic("Sandbox reconciler operates on sandbox runtime resources, not workspace-scoped data")
public class SandboxReconciler {

    private static final Logger log = LoggerFactory.getLogger(SandboxReconciler.class);
    private static final String MDC_RECONCILER_TYPE = "reconciler.type";

    private static final Duration REAP_GRACE = Duration.ofMinutes(2);

    private final ReviewJobRepository jobRepository;
    private final SandboxBackend backend;
    private final Clock clock;
    private final Counter orphanedSandboxes;
    private final Counter completedSweeps;
    private final Counter skippedSweeps;
    private final Timer reconciliationDuration;

    public SandboxReconciler(
            ReviewJobRepository jobRepository, SandboxBackend backend, MeterRegistry meterRegistry, Clock clock) {
        this.jobRepository = jobRepository;
        this.backend = backend;
        this.clock = clock;
        this.orphanedSandboxes = Counter.builder(AgentMetrics.SANDBOX_RECONCILER_ORPHANED)
                .tag("resource", "sandbox")
                .description("Orphaned sandboxes removed")
                .register(meterRegistry);
        this.completedSweeps = Counter.builder(AgentMetrics.SANDBOX_RECONCILER_SWEEPS)
                .tag("outcome", "completed")
                .description("Reconciliation sweeps that ran to completion")
                .register(meterRegistry);
        this.skippedSweeps = Counter.builder(AgentMetrics.SANDBOX_RECONCILER_SWEEPS)
                .tag("outcome", "skipped")
                .description("Reconciliation sweeps skipped because an inventory they depend on was unreadable")
                .register(meterRegistry);
        this.reconciliationDuration = Timer.builder(AgentMetrics.SANDBOX_RECONCILER_DURATION)
                .description("Duration of periodic reconciliation sweeps")
                .register(meterRegistry);
    }

    /** Removes what an earlier process left, before the periodic sweep would. */
    public void onStartup() {
        MDC.put(MDC_RECONCILER_TYPE, "startup");
        try {
            log.info("Sandbox reconciler: startup check");
            sweep();
        } finally {
            MDC.remove(MDC_RECONCILER_TYPE);
        }
    }

    public void periodicReconciliation() {
        MDC.put(MDC_RECONCILER_TYPE, "periodic");
        try {
            reconciliationDuration.record(this::sweep);
        } finally {
            MDC.remove(MDC_RECONCILER_TYPE);
        }
    }

    private void sweep() {
        Map<UUID, Integer> activeAttempts;
        List<SandboxBackend.Listed> sandboxes;
        try {
            activeAttempts =
                    jobRepository.findByStatusIn(List.of(ReviewJobStatus.QUEUED, ReviewJobStatus.RUNNING)).stream()
                            .collect(Collectors.toMap(ReviewJob::getId, ReviewJob::getAttempt));
            sandboxes = backend.list();
        } catch (RuntimeException e) {
            log.warn(
                    "Skipping sandbox reconciliation — the jobs or the sandboxes are unreadable: errorType={}",
                    e.getClass().getName());
            skippedSweeps.increment();
            return;
        }
        for (SandboxBackend.Listed sandbox : sandboxes) {
            if (abandoned(sandbox, activeAttempts)) {
                remove(sandbox);
            }
        }
        completedSweeps.increment();
    }

    private boolean abandoned(SandboxBackend.Listed sandbox, Map<UUID, Integer> activeAttempts) {
        return switch (sandbox.kind()) {
            case REVIEW -> {
                Integer current = activeAttempts.get(sandbox.id());
                Integer attempt = sandbox.attempt();
                yield current == null ? !isYoung(sandbox.created()) : attempt != null && attempt < current;
            }
            case HOST -> sandbox.creatorGone();
        };
    }

    private boolean isYoung(@Nullable Instant created) {
        return created == null || created.isAfter(clock.instant().minus(REAP_GRACE));
    }

    private void remove(SandboxBackend.Listed sandbox) {
        try {
            backend.remove(sandbox.handle());
            log.warn(
                    "Removed an orphaned sandbox: sandbox={}, kind={}",
                    sandbox.handle().id(),
                    sandbox.kind());
            orphanedSandboxes.increment();
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to remove an orphaned sandbox {}: errorType={}",
                    sandbox.handle().id(),
                    e.getClass().getName());
        }
    }
}
