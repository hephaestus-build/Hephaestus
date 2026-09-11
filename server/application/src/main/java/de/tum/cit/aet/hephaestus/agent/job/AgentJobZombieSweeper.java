package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.StuckDeliveryRow;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Periodic recovery for {@code agent_job} rows stuck RUNNING or stuck mid-delivery.
 *
 * <p><b>No {@code @SchedulerLock} on anything in this class, deliberately.</b> Every sweep here acts
 * through a conditional UPDATE that names the state it expects, so two replicas sweeping the same row
 * produce one winner and one no-op; delivery recovery additionally claims via an attempt-counter CAS
 * BEFORE any external post. Any {@code @Scheduled} method added here must earn that the same way, or
 * take the lock.
 */
@ConditionalOnServerRole
@Component
@ConditionalOnProperty(prefix = "hephaestus.agent", name = "enabled", havingValue = "true")
@WorkspaceAgnostic("Zombie sweeper operates across all workspaces")
public class AgentJobZombieSweeper {

    private static final Logger log = LoggerFactory.getLogger(AgentJobZombieSweeper.class);

    private static final Duration RUNNING_BUFFER = Duration.ofMinutes(5);

    /** Grace before a RUNNING job is judged orphaned, so a (re)started worker can write its first heartbeat. */
    private static final Duration ORPHAN_STARTUP_GRACE = Duration.ofSeconds(120);

    /**
     * Must stay well above {@link AgentProperties#WORKER_LEASE_TTL} so a dead worker's jobs are requeued
     * before its registry row goes.
     */
    private static final Duration STALE_REGISTRATION_TTL = Duration.ofHours(1);

    private static final Duration DELIVERY_PENDING_STUCK_THRESHOLD = Duration.ofMinutes(10);

    static final int MAX_DELIVERY_RECOVERY_ATTEMPTS = 3;

    private static final int DELIVERY_RECOVERY_BATCH_SIZE = 50;

    /** Timeout applied when a job's frozen snapshot cannot be read. */
    private static final int FALLBACK_TIMEOUT_SECONDS = 600;

    private final AgentJobRepository jobRepository;
    private final WorkerRegistryRepository workerRegistryRepository;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final AgentJobLifecycleService lifecycleService;
    private final LlmUsageRecorder usageRecorder;
    private final Counter zombieReaped;
    private final Counter orphanRequeued;
    private final Counter orphanFailed;
    private final Counter deliveryRecovered;
    private final Counter snapshotUnreadable;
    private final AgentJobTelemetry jobTelemetry;

    @Autowired
    public AgentJobZombieSweeper(
            AgentJobRepository jobRepository,
            WorkerRegistryRepository workerRegistryRepository,
            AgentProperties agentProperties,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            AgentJobLifecycleService lifecycleService,
            LlmUsageRecorder usageRecorder,
            MeterRegistry meterRegistry,
            AgentJobTelemetry jobTelemetry) {
        this.jobRepository = jobRepository;
        this.workerRegistryRepository = workerRegistryRepository;
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.lifecycleService = lifecycleService;
        this.usageRecorder = usageRecorder;
        this.zombieReaped = Counter.builder(AgentMetrics.AGENT_JOB_ZOMBIE_REAPED)
                .description("Stale RUNNING jobs marked as TIMED_OUT")
                .register(meterRegistry);
        this.orphanRequeued = Counter.builder(AgentMetrics.AGENT_JOB_ORPHAN_REQUEUED)
                .description("RUNNING jobs whose owning worker was lost, requeued for another worker")
                .register(meterRegistry);
        this.orphanFailed = Counter.builder(AgentMetrics.AGENT_JOB_ORPHAN_FAILED)
                .description("Orphaned jobs that hit the retry cap and were failed")
                .register(meterRegistry);
        this.deliveryRecovered = Counter.builder(AgentMetrics.AGENT_JOB_DELIVERY_RECOVERED)
                .description("Stuck PENDING deliveries successfully re-attempted by the recovery sweep")
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
        List<AgentJob> staleJobs = jobRepository.findStaleRunningJobs(conservativeCutoff);

        if (staleJobs.isEmpty()) {
            return;
        }

        // Per-job transaction, never a method-level @Transactional: one poison job must not roll back
        // the jobs the batch already reaped (and their ledger events) at commit time.
        for (AgentJob job : staleJobs) {
            try {
                AgentJob reaped = transactionTemplate.execute(status -> reapIfStale(job.getId()));
                if (reaped != null) {
                    // The sweeper thread has no ambient trace context; restore the job's own trace ID
                    // (with a fresh span so no stale scheduler-thread value pairs with it) so the
                    // terminal event joins the lifecycle it closes.
                    MDC.put(StructuredLogKeys.TRACE_ID, reaped.getTraceId());
                    MDC.put(StructuredLogKeys.SPAN_ID, AgentJobExecutor.randomSpanId());
                    try {
                        jobTelemetry.terminal(reaped, AgentJobStatus.TIMED_OUT, AgentJobTelemetry.age(reaped));
                    } finally {
                        MDC.remove(StructuredLogKeys.TRACE_ID);
                        MDC.remove(StructuredLogKeys.SPAN_ID);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to reap stale job: jobId={}, error={}", job.getId(), e.getMessage());
            }
        }
    }

    private @Nullable AgentJob reapIfStale(UUID jobId) {
        AgentJob lockedJob = jobRepository.findByIdWithWorkspaceForUpdate(jobId).orElse(null);
        if (lockedJob == null || lockedJob.getStatus() != AgentJobStatus.RUNNING) return null;
        int timeoutSeconds = getTimeoutFromSnapshot(lockedJob);
        Duration maxLifetime = Duration.ofSeconds(timeoutSeconds).plus(RUNNING_BUFFER);
        if (lockedJob.getStartedAt() != null
                && lockedJob.getStartedAt().plus(maxLifetime).isAfter(Instant.now())) {
            return null;
        }

        int updated = jobRepository.transitionStatus(
                jobId,
                AgentJobStatus.TIMED_OUT,
                Instant.now(),
                "Reaped: exceeded timeout (executor may have crashed)",
                Set.of(AgentJobStatus.RUNNING));

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
                if (orphan.getRetryCount() >= agentProperties.maxRetries()) {
                    Integer failed = transactionTemplate.execute(s -> {
                        AgentJob job = jobRepository
                                .findByIdWithWorkspaceForUpdate(orphan.getJobId())
                                .orElse(null);
                        if (job == null) return 0;
                        int rows = jobRepository.transitionStatus(
                                orphan.getJobId(),
                                AgentJobStatus.FAILED,
                                Instant.now(),
                                "Orphaned: owning worker lost and retry limit reached",
                                Set.of(AgentJobStatus.RUNNING));
                        if (rows > 0) recordUnverifiableUsage(job);
                        return rows;
                    });
                    if (failed != null && failed > 0) {
                        orphanFailed.increment();
                        log.warn(
                                "Orphaned job {} hit retry cap ({}); failed",
                                orphan.getJobId(),
                                orphan.getRetryCount());
                    }
                    continue;
                }
                int attemptNumber = orphan.getRetryCount() + 1;
                Instant availableAt = Instant.now().plus(AgentJobBackoff.compute(attemptNumber));
                String newToken = AgentJob.generateJobToken();
                String newTokenHash = AgentJob.computeTokenHash(newToken);
                Integer requeued = transactionTemplate.execute(s -> {
                    AgentJob job = jobRepository
                            .findByIdWithWorkspaceForUpdate(orphan.getJobId())
                            .orElse(null);
                    if (job == null) return 0;
                    // Read the token counts BEFORE requeuing: requeueOrphan zeroes the row's accumulators.
                    AgentJobLlmUsage counts = job.getExecutionStartedAt() != null
                            ? jobRepository.findLlmUsageById(job.getId()).orElse(null)
                            : null;
                    int rows = jobRepository.requeueOrphan(
                            orphan.getJobId(),
                            orphan.getWorkerId(),
                            agentProperties.maxRetries(),
                            availableAt,
                            newToken,
                            newTokenHash);
                    if (rows > 0) recordUnverifiableUsage(job, counts);
                    return rows;
                });
                if (requeued != null && requeued > 0) {
                    orphanRequeued.increment();
                    log.warn("Requeued orphaned job {} (retry {})", orphan.getJobId(), orphan.getRetryCount() + 1);
                }
            } catch (Exception e) {
                log.warn("Failed to recover orphaned job {}: {}", orphan.getJobId(), e.getMessage());
            }
        }
    }

    /**
     * Re-attempts delivery for jobs stuck at {@code delivery_status=PENDING}, which
     * {@link AgentJobLifecycleService#retryDelivery} cannot reach because it CASes from FAILED. Once
     * {@link #MAX_DELIVERY_RECOVERY_ATTEMPTS} is spent the delivery is marked FAILED, which both stops
     * the sweep and makes the manual retry endpoint applicable.
     */
    @Scheduled(fixedDelay = 5, initialDelay = 3, timeUnit = TimeUnit.MINUTES)
    public void recoverStuckDeliveries() {
        Instant cutoff = Instant.now().minus(DELIVERY_PENDING_STUCK_THRESHOLD);
        List<StuckDeliveryRow> stuck =
                jobRepository.findStuckPendingDeliveries(cutoff, PageRequest.of(0, DELIVERY_RECOVERY_BATCH_SIZE));
        if (stuck.isEmpty()) {
            return;
        }
        log.warn("Found {} agent job(s) stuck at delivery_status=PENDING; attempting recovery", stuck.size());
        for (StuckDeliveryRow candidate : stuck) {
            try {
                if (candidate.getDeliveryAttempts() >= MAX_DELIVERY_RECOVERY_ATTEMPTS) {
                    transactionTemplate.executeWithoutResult(s -> jobRepository.updateDeliveryStatus(
                            candidate.getId(), DeliveryStatus.FAILED, candidate.getDeliveryCommentId()));
                    log.warn(
                            "Delivery recovery exhausted after {} attempt(s); marking FAILED: jobId={}",
                            candidate.getDeliveryAttempts(),
                            candidate.getId());
                    continue;
                }
                short expectedAttempts = candidate.getDeliveryAttempts();
                Integer claimed = transactionTemplate.execute(
                        s -> jobRepository.claimDeliveryRecoveryAttempt(candidate.getId(), expectedAttempts));
                if (claimed == null || claimed == 0) {
                    continue; // a concurrent sweeper replica already claimed this pass's attempt
                }
                // Only now is the job worth reading whole: everything above decided from three columns,
                // and the delivery itself needs the output the review produced.
                AgentJob job = jobRepository
                        .findDeliveryRecoveryCandidate(candidate.getId())
                        .orElse(null);
                if (job == null) {
                    continue; // it finished, failed or was removed between the sweep and this attempt
                }
                // The CAS's post-increment value is THIS attempt's fence token for its terminal write.
                short claimedAttempts = (short) (expectedAttempts + 1);
                boolean delivered = lifecycleService.recoverStuckDelivery(job, claimedAttempts);
                if (delivered) {
                    deliveryRecovered.increment();
                }
            } catch (Exception e) {
                log.warn("Delivery recovery pass failed for job {}: {}", candidate.getId(), e.getMessage());
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

    /**
     * Read a job's frozen {@link ConfigSnapshot}, or {@code null} when it cannot be read — which is an
     * expected state, not corruption ({@link ConfigSnapshot#fromJson} rejects a snapshot written by a
     * newer schema version). Never fatal here: refusing to terminalise such a job leaves it RUNNING
     * forever.
     */
    private @Nullable ConfigSnapshot parseSnapshot(AgentJob job) {
        if (job.getConfigSnapshot() == null) {
            return null;
        }
        try {
            return ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper);
        } catch (RuntimeException e) {
            log.warn("Could not read config snapshot for job {}: {}", job.getId(), e.getMessage());
            return null;
        }
    }

    private int getTimeoutFromSnapshot(AgentJob job) {
        ConfigSnapshot snapshot = parseSnapshot(job);
        return snapshot != null ? snapshot.timeoutSeconds() : FALLBACK_TIMEOUT_SECONDS;
    }

    private void recordUnverifiableUsage(AgentJob job) {
        recordUnverifiableUsage(
                job,
                job.getExecutionStartedAt() != null
                        ? jobRepository.findLlmUsageById(job.getId()).orElse(null)
                        : null);
    }

    /**
     * Bill a reaped attempt from token counts the caller read BEFORE any requeue of the job (see
     * {@link AgentJobRepository#requeueOrphan}, which zeroes the row's accumulators).
     *
     * <p><b>Must not throw.</b> Every caller runs it inside the same transaction as the state
     * transition it accompanies, so an exception here rolls that transition back and the job returns to
     * RUNNING to fail identically on the next sweep, forever.
     */
    private void recordUnverifiableUsage(AgentJob job, @Nullable AgentJobLlmUsage counts) {
        if (job.getExecutionStartedAt() == null) return;
        ConfigSnapshot snapshot = parseSnapshot(job);
        if (snapshot == null) {
            snapshotUnreadable.increment();
        }
        LlmPriceSnapshot price = snapshot != null && snapshot.priceSnapshot() != null
                ? snapshot.priceSnapshot()
                : LlmPriceSnapshot.unpricedInstance();
        TerminalUsage.resolve(null, counts)
                .appendTo(
                        usageRecorder,
                        job.getWorkspace().getId(),
                        job,
                        snapshot != null ? snapshot.upstreamModelId() : null,
                        price);
    }
}
