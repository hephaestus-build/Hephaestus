package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@ConditionalOnServerRole
@Component
@ConditionalOnBooleanProperty(RuntimeRole.AGENT_ENABLED_PROPERTY)
@WorkspaceAgnostic("Zombie sweeper operates across all workspaces")
public class ReviewJobZombieSweeper {

    private static final Logger log = LoggerFactory.getLogger(ReviewJobZombieSweeper.class);

    /** How long past its job timeout a running attempt is left before the sweep ends it. */
    static final Duration RUNNING_BUFFER = SandboxLayout.RESULT_UPLOAD_GRACE.plusMinutes(5);

    // Allow startup time for a claimed worker to publish its first heartbeat.
    private static final Duration ORPHAN_STARTUP_GRACE = Duration.ofMinutes(2);

    // Retain registry rows beyond the worker lease so orphan recovery can run before deletion.
    private static final Duration STALE_REGISTRATION_TTL = Duration.ofHours(1);

    private static final int FALLBACK_TIMEOUT_SECONDS = 600;

    private final ReviewJobRepository jobRepository;
    private final ReviewJobRecovery recovery;
    private final WorkerRegistryRepository workerRegistryRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ReviewAttemptUsageService attemptUsage;
    private final Counter zombieReaped;
    private final Counter orphanRequeued;
    private final Counter orphanFailed;
    private final Counter compositionUnrecorded;
    private final Counter snapshotUnreadable;
    private final ReviewJobTelemetry jobTelemetry;

    @Autowired
    public ReviewJobZombieSweeper(
            ReviewJobRepository jobRepository,
            WorkerRegistryRepository workerRegistryRepository,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            ReviewAttemptUsageService attemptUsage,
            MeterRegistry meterRegistry,
            ReviewJobTelemetry jobTelemetry,
            ReviewJobRecovery recovery) {
        this.jobRepository = jobRepository;
        this.recovery = recovery;
        this.workerRegistryRepository = workerRegistryRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.attemptUsage = attemptUsage;
        this.zombieReaped = Counter.builder(AgentMetrics.AGENT_JOB_ZOMBIE_REAPED)
                .description("Stale RUNNING jobs marked as TIMED_OUT")
                .register(meterRegistry);
        this.orphanRequeued = Counter.builder(AgentMetrics.AGENT_JOB_ORPHAN_REQUEUED)
                .description("RUNNING jobs whose owning worker was lost, requeued for another worker")
                .register(meterRegistry);
        this.orphanFailed = Counter.builder(AgentMetrics.AGENT_JOB_ORPHAN_FAILED)
                .description("Orphaned jobs that hit the retry cap and were failed")
                .register(meterRegistry);
        this.compositionUnrecorded = Counter.builder(AgentMetrics.AGENT_JOB_COMPOSITION_STOPPED)
                .description("Destinations of an admitted review left without a recorded reason")
                .tag("cause", "retries_exhausted")
                .tag("outcome", "unrecorded")
                .register(meterRegistry);
        this.snapshotUnreadable = Counter.builder(AgentMetrics.AGENT_JOB_SNAPSHOT_UNREADABLE)
                .description("Terminal accounting events whose config snapshot could not be read; billed UNPRICED")
                .register(meterRegistry);
        this.jobTelemetry = jobTelemetry;
    }

    /**
     * Absolute-timeout backstop for the case where {@link #recoverOrphanedJobs} cannot run at all: mark
     * RUNNING jobs {@code TIMED_OUT} once they exceed their per-job timeout + {@link #RUNNING_BUFFER}.
     */
    @Scheduled(fixedDelay = 2, timeUnit = TimeUnit.MINUTES, initialDelay = 1)
    public void reapStaleRunningJobs() {
        Instant conservativeCutoff = Instant.now().minus(RUNNING_BUFFER);
        List<ReviewJob> staleJobs = jobRepository.findStaleRunningJobs(conservativeCutoff);

        if (staleJobs.isEmpty()) {
            return;
        }

        // Per-job transaction, never a method-level @Transactional: one poison job must not roll back
        // the jobs the batch already reaped (and their ledger events) at commit time.
        for (ReviewJob job : staleJobs) {
            try {
                ReviewJob reaped = transactionTemplate.execute(status -> reapIfStale(job.getId()));
                if (reaped != null) {
                    // Scheduler threads have no job trace context; restore it with a fresh span.
                    MDC.put(StructuredLogKeys.TRACE_ID, reaped.getTraceId());
                    MDC.put(StructuredLogKeys.SPAN_ID, ReviewJobExecutor.randomSpanId());
                    try {
                        jobTelemetry.terminal(reaped, ReviewJobStatus.TIMED_OUT, ReviewJobTelemetry.age(reaped));
                    } finally {
                        MDC.remove(StructuredLogKeys.TRACE_ID);
                        MDC.remove(StructuredLogKeys.SPAN_ID);
                    }
                }
            } catch (Exception e) {
                log.warn(
                        "Failed to reap stale job: jobId={}, errorType={}",
                        job.getId(),
                        e.getClass().getName());
            }
        }
    }

    private @Nullable ReviewJob reapIfStale(UUID jobId) {
        ReviewJob lockedJob = jobRepository.lockById(jobId).orElse(null);
        if (lockedJob == null || lockedJob.getStatus() != ReviewJobStatus.RUNNING) return null;
        int timeoutSeconds = getTimeoutFromSnapshot(lockedJob);
        Duration maxLifetime = Duration.ofSeconds(timeoutSeconds).plus(RUNNING_BUFFER);
        Instant deadlineAnchor = lockedJob.getExecutionStartedAt() != null
                ? lockedJob.getExecutionStartedAt()
                : lockedJob.getStartedAt();
        if (deadlineAnchor != null && deadlineAnchor.plus(maxLifetime).isAfter(Instant.now())) {
            return null;
        }

        int updated = jobRepository.transitionStatus(
                jobId,
                ReviewJobStatus.TIMED_OUT,
                Instant.now(),
                "The job exceeded its timeout. The executor may have crashed.",
                Set.of(ReviewJobStatus.RUNNING));

        if (updated > 0) {
            recordUnverifiableUsage(lockedJob);
            zombieReaped.increment();
            log.warn("Reaped stale RUNNING job: jobId={}, startedAt={}", jobId, lockedJob.getStartedAt());
            return lockedJob;
        }
        return null;
    }

    /**
     * Requeue RUNNING jobs whose owning worker stopped heartbeating, so a sibling picks them up instead
     * of waiting out the full job timeout; jobs past the retry cap are failed instead.
     */
    @Scheduled(fixedDelay = 20, timeUnit = TimeUnit.SECONDS, initialDelay = 30)
    public void recoverOrphanedJobs() {
        List<OrphanedJobRef> orphans = jobRepository.findOrphanedRunningJobs(
                Instant.now().minus(ORPHAN_STARTUP_GRACE), AgentProperties.WORKER_LEASE_TTL.toSeconds());
        if (orphans.isEmpty()) {
            return;
        }
        log.warn("Found {} orphaned RUNNING job(s) (owning worker lost); recovering", orphans.size());
        for (OrphanedJobRef orphan : orphans) {
            try {
                ReviewJobRecovery.Outcome outcome = transactionTemplate.execute(
                        s -> recovery.recover(orphan.getJobId(), orphan.getWorkerId(), orphan.getAttempt(), null));
                if (outcome == ReviewJobRecovery.Outcome.REQUEUED) {
                    orphanRequeued.increment();
                    log.warn("Requeued orphaned job {} (retry {})", orphan.getJobId(), orphan.getAttempt() + 1);
                } else if (outcome == ReviewJobRecovery.Outcome.COMPLETED) {
                    // The attempt after the retry limit was lost too: the review completes with what is still open
                    // left without a recorded reason.
                    compositionUnrecorded.increment();
                    log.warn("Orphaned job {} completed with its open feedback unrecorded", orphan.getJobId());
                } else if (outcome == ReviewJobRecovery.Outcome.FAILED) {
                    orphanFailed.increment();
                    log.warn("Orphaned job {} failed at attempt {}", orphan.getJobId(), orphan.getAttempt());
                }
            } catch (Exception e) {
                log.warn(
                        "Failed to recover orphaned job {}: errorType={}",
                        orphan.getJobId(),
                        e.getClass().getName());
            }
        }
    }

    /**
     * Bound {@code worker_registry} growth: no worker ever deletes its own row (see
     * {@link WorkerLivenessReporter#stop()}), so every departed worker is reaped here once its heartbeat
     * has been stale for {@link #STALE_REGISTRATION_TTL}.
     */
    @Scheduled(fixedDelay = 60, initialDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void purgeStaleWorkerRegistrations() {
        Integer removed = transactionTemplate.execute(
                s -> workerRegistryRepository.deleteStale(STALE_REGISTRATION_TTL.toSeconds()));
        if (removed != null && removed > 0) {
            log.info("Purged {} stale worker_registry row(s)", removed);
        }
    }

    // Corrupt configuration must not prevent terminal recovery.
    private @Nullable ConfigSnapshot parseSnapshot(ReviewJob job) {
        if (job.getConfigSnapshot() == null) {
            return null;
        }
        try {
            return ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not read config snapshot for job {}: errorType={}",
                    job.getId(),
                    e.getClass().getName());
            return null;
        }
    }

    private int getTimeoutFromSnapshot(ReviewJob job) {
        ConfigSnapshot snapshot = parseSnapshot(job);
        return snapshot != null ? snapshot.timeoutSeconds() : FALLBACK_TIMEOUT_SECONDS;
    }

    private void recordUnverifiableUsage(ReviewJob job) {
        recordUnverifiableUsage(
                job,
                job.getExecutionStartedAt() != null
                        ? jobRepository.findLlmUsageById(job.getId()).orElse(null)
                        : null);
    }

    // Read counters before requeue clears them.
    // Accounting must not throw and roll back the terminal transition.
    private void recordUnverifiableUsage(ReviewJob job, @Nullable ReviewJobLlmUsage counts) {
        if (job.getExecutionStartedAt() == null) return;
        ConfigSnapshot snapshot = parseSnapshot(job);
        if (snapshot == null) {
            snapshotUnreadable.increment();
        }
        LlmPriceSnapshot price = snapshot != null && snapshot.model().priceSnapshot() != null
                ? snapshot.model().priceSnapshot()
                : LlmPriceSnapshot.unpricedInstance();
        attemptUsage.settleAttempt(job, FrozenCharge.admitted(snapshot, price), null, counts);
    }
}
