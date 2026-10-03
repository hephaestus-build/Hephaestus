package de.tum.cit.aet.hephaestus.integration.core.sync;

import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ExecutorConfigurationSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared sync-job execution template. Bookkeeping uses {@link TransactionTemplate} because methods
 * self-invoke; long-running runner bodies execute outside a transaction.
 */
@Service
public class SyncJobService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SyncJobService.class);

    private static final Duration ABANDON_THRESHOLD = Duration.ofMinutes(15);

    private static final int RETENTION_LIMIT = 50;

    private static final int ERROR_SUMMARY_MAX_LENGTH = 2000;

    private final SyncJobRepository syncJobRepository;
    private final ConnectionRepository connectionRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    /**
     * Handles of the jobs whose bodies are executing in THIS JVM, by job id. Registration is what
     * makes a job "locally owned": {@link #reapAbandoned} skips these rows, and cancel requests
     * landing on this pod reach the runner through the handle instead of waiting out the lease
     * heartbeat.
     *
     * <p>Deliberately holds the handle only — never the runner's {@link Thread}. Cancellation here is
     * cooperative by construction: an interrupt is a no-op on a platform thread blocked in a pgjdbc
     * socket read (the case where it would be useful), and on a virtual thread it closes the socket
     * out from under the driver, which would destroy the runner's own terminal write.
     */
    private final Map<Long, SyncJobHandle> activeHandles = new ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(true);

    /**
     * Source of "now" handed to every {@link SyncJobHandle} for its write-throttle window. Held here
     * rather than injected as a Spring bean because this service loads in every runtime role and the
     * {@code Clock} bean is server-role-scoped; tests use the package-private constructor to advance a
     * controllable clock instead of sleeping.
     */
    private final Clock clock;

    // @Autowired marks this as the constructor Spring injects; the second (Clock-taking) constructor is a
    // test seam, so without this marker Spring sees two candidates and falls back to a no-arg it can't find.
    @Autowired
    public SyncJobService(
            SyncJobRepository syncJobRepository,
            ConnectionRepository connectionRepository,
            WorkspaceRepository workspaceRepository,
            ApplicationEventPublisher eventPublisher,
            TransactionTemplate transactionTemplate) {
        this(
                syncJobRepository,
                connectionRepository,
                workspaceRepository,
                eventPublisher,
                transactionTemplate,
                Clock.systemUTC());
    }

    SyncJobService(
            SyncJobRepository syncJobRepository,
            ConnectionRepository connectionRepository,
            WorkspaceRepository workspaceRepository,
            ApplicationEventPublisher eventPublisher,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        this.syncJobRepository = syncJobRepository;
        this.connectionRepository = connectionRepository;
        this.workspaceRepository = workspaceRepository;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public record Started(SyncJob job, SyncJobHandle handle) {}

    /** Creates a PENDING job after reaping a stale predecessor and enforcing the active-job guard. */
    public Started beginJob(SyncJobRequest request) {
        long connectionId = request.connectionId();
        reapAbandonedForConnection(connectionId);

        SyncJob job;
        try {
            job = transactionTemplate.execute(status -> {
                Workspace workspace = workspaceRepository
                        .findByIdForUpdate(request.workspaceId())
                        .orElseThrow(
                                () -> new EntityNotFoundException("Workspace", String.valueOf(request.workspaceId())));
                if (workspace.getStatus() != Workspace.WorkspaceStatus.ACTIVE) {
                    throw new SyncStateConflictException(
                            "Cannot start sync for workspace " + request.workspaceId()
                                    + " in status "
                                    + workspace.getStatus(),
                            Map.of("workspaceId", request.workspaceId(), "workspaceStatus", workspace.getStatus()));
                }
                connectionRepository.acquireLifecycleLock(connectionId, request.workspaceId());
                var connection = connectionRepository
                        .findByIdAndWorkspaceId(connectionId, request.workspaceId())
                        .orElseThrow(() -> new EntityNotFoundException("Connection", connectionId));
                if (connection.getState() != IntegrationState.ACTIVE) {
                    throw new SyncStateConflictException(
                            "Cannot start sync for connection " + connectionId + " in state " + connection.getState(),
                            Map.of("connectionId", connectionId, "connectionState", connection.getState()));
                }
                Optional<SyncJob> active = syncJobRepository.findFirstByConnection_IdAndStatusInOrderByCreatedAtDesc(
                        connectionId, SyncJobStatus.ACTIVE);
                if (active.isPresent()) {
                    throw new SyncJobConflictException(active.get());
                }
                SyncJob created = syncJobRepository.saveAndFlush(new SyncJob(
                        connection.getWorkspace(),
                        connection,
                        connection.getKind(),
                        request.type(),
                        request.trigger(),
                        request.triggeredByUserId()));
                // Must be published in-transaction for the AFTER_COMMIT listener.
                publish(request.workspaceId(), connectionId, created.getKind(), SyncStateChangedEvent.Scope.JOB);
                return created;
            });
        } catch (DataIntegrityViolationException e) {
            // Re-read the partial-index race winner in a fresh transaction; the insert transaction aborted.
            Optional<SyncJob> raced = Optional.ofNullable(transactionTemplate.execute(
                            status -> syncJobRepository.findFirstByConnection_IdAndStatusInOrderByCreatedAtDesc(
                                    connectionId, SyncJobStatus.ACTIVE)))
                    .orElseGet(Optional::empty);
            if (raced.isPresent()) {
                throw new SyncJobConflictException(raced.get(), e);
            }
            throw e;
        }

        SyncJobHandle handle = new SyncJobHandle(job.getId(), this::persistProgress, clock);
        return new Started(job, handle);
    }

    /**
     * Runs a prepared job body and records its terminal outcome instead of propagating runner failures.
     *
     * <p>The rule for a body that RAN: its outcome is the <em>runner's</em> to report, never a
     * canceller's. Only a body that called {@link SyncJobHandle#reportCancelled()} is recorded
     * CANCELLED. A body that returns normally is SUCCEEDED even if a cancel request committed while it
     * was finishing — from any replica, or from {@link #stop()} — because its work is done and its
     * watermarks are advanced, and calling that "cancelled" would hide a real success from
     * {@code lastSuccessfulJob}. A body that throws is FAILED with the real reason, even mid-shutdown:
     * a vendor error that happens to land during a deploy is a vendor error.
     *
     * <p>The one case this method decides itself is a body that never ran at all: if shutdown began
     * before the body was invoked, nothing was attempted, so CANCELLED is the honest record and there
     * is no runner report to defer to.
     *
     * <p>Cancellation is therefore a <em>request</em>, not an outcome: {@code cancel_requested} asks the
     * runner to stop, and only the runner's own report decides whether it did.
     */
    public void executeBody(Started started, Consumer<? super SyncJobHandle> body) {
        long jobId = started.job().getId();
        SyncJobHandle handle = started.handle();
        boolean registered = false;
        try {
            MarkRunningResult start = markRunning(jobId);
            if (!start.started()) {
                return;
            }
            // Register only once the job is RUNNING, so a rejected dispatch cannot keep a PENDING
            // lease alive and the reaper's local-ownership skip only covers bodies that really started.
            activeHandles.put(jobId, handle);
            registered = true;
            handle.refreshCancellation(start.cancelRequested());
            if (!running.get()) {
                // Shutdown began before the body ran. Nothing was attempted, so this runner can
                // honestly report CANCELLED itself — no interrupt, no outcome written by a canceller.
                handle.refreshCancellation(true);
                completeJob(jobId, SyncJobStatus.CANCELLED, null, handle);
                return;
            }
            body.accept(handle);
            SyncJobStatus finalStatus = handle.cancelledReported()
                    ? SyncJobStatus.CANCELLED
                    : handle.warningsReported() ? SyncJobStatus.SUCCEEDED_WITH_WARNINGS : SyncJobStatus.SUCCEEDED;
            completeJob(jobId, finalStatus, null, handle);
        } catch (Exception e) {
            if (handle.cancelledReported()) {
                log.info("Sync job {} aborted after its runner observed cancellation: {}", jobId, e.toString());
                completeJob(jobId, SyncJobStatus.CANCELLED, null, handle);
                return;
            }
            log.warn("Sync job {} failed: {}", jobId, e.toString(), e);
            completeJob(jobId, SyncJobStatus.FAILED, truncate(summarize(e)), handle);
        } finally {
            if (registered) {
                activeHandles.remove(jobId);
            }
            pruneRetention(started.job().getConnection().getId());
        }
    }

    public void failStarted(Started started, String reason) {
        completeJob(started.job().getId(), SyncJobStatus.FAILED, truncate(reason), started.handle());
    }

    public void run(SyncJobRequest request, Consumer<? super SyncJobHandle> body) {
        Started started = beginJob(request);
        executeBody(started, body);
    }

    /**
     * Cooperative cancel: flips {@code cancel_requested} and, if the job is running in THIS JVM,
     * refreshes its handle immediately (no need to wait out the 60s lease-heartbeat sweep for the
     * common case where the request lands on the same pod that's executing the job).
     *
     * @throws EntityNotFoundException     if the job doesn't exist in this workspace
     * @throws SyncStateConflictException if the job is already terminal (409)
     */
    public void requestCancel(long workspaceId, long jobId) {
        transactionTemplate.executeWithoutResult(status -> {
            SyncJob job = syncJobRepository
                    .findByIdAndWorkspace_Id(jobId, workspaceId)
                    .orElseThrow(() -> new EntityNotFoundException("SyncJob", jobId));
            if (!SyncJobStatus.ACTIVE.contains(job.getStatus())) {
                throw new SyncStateConflictException(
                        "Cannot cancel sync job " + jobId + ". It already has the terminal status " + job.getStatus()
                                + ".",
                        Map.of("jobId", jobId, "jobStatus", job.getStatus()));
            }
            // Targeted flag-only UPDATE (not a full-row save): a full save would write back this stale
            // snapshot's status column and could resurrect a job that the executor just completed.
            if (syncJobRepository.markCancelRequested(jobId, SyncJobStatus.ACTIVE) == 0) {
                throw new SyncStateConflictException(
                        "Cannot cancel sync job " + jobId + ". It completed at the same time.", Map.of("jobId", jobId));
            }
            publish(workspaceId, job.getConnection().getId(), job.getKind(), SyncStateChangedEvent.Scope.JOB);
        });

        notifyLocalRunner(jobId);
    }

    /**
     * Fences connection teardown: reaps leases the hourly sweep hasn't reached yet, then requests
     * cooperative cancellation of whatever job still holds the connection.
     *
     * <p>This is the admin-disconnect door's helper, and it exists so that door never wedges. The
     * inline reap mirrors {@link #beginJob}'s, so a job stranded RUNNING by a pod crash frees the
     * connection as soon as its lease expires rather than at the next hourly sweep. The cancel request
     * is what makes a 409 recoverable: the admin retries and the second attempt finds the connection
     * free, instead of being told "busy" forever with nothing they can do about it.
     *
     * <p>Residual, by design: a runner wedged in a vendor call with no socket timeout never observes
     * the flag and keeps heartbeating, so it is neither reapable nor cancellable. Its row clears on the
     * next restart, when {@link SyncJobZombieSweeper}'s startup sweep runs with an empty local registry.
     * We do not interrupt it — see {@link #activeHandles}.
     *
     * @return the id of the job still holding the connection, empty when the connection is free
     */
    public Optional<Long> requestCancelForTeardown(long connectionId) {
        reapAbandonedForConnection(connectionId);
        Optional<Long> blockingJobId = Optional.ofNullable(transactionTemplate.execute(status -> syncJobRepository
                        .findFirstByConnection_IdAndStatusInOrderByCreatedAtDesc(connectionId, SyncJobStatus.ACTIVE)
                        .flatMap(job -> {
                            if (syncJobRepository.markCancelRequested(job.getId(), SyncJobStatus.ACTIVE) == 0) {
                                // Completed between the read and the write — the connection is free after all.
                                return Optional.empty();
                            }
                            publish(
                                    job.getWorkspace().getId(),
                                    connectionId,
                                    job.getKind(),
                                    SyncStateChangedEvent.Scope.JOB);
                            return Optional.of(job.getId());
                        })))
                .orElseGet(Optional::empty);
        return blockingJobId.map(jobId -> {
            notifyLocalRunner(jobId);
            return jobId;
        });
    }

    /**
     * Hands a committed cancel request to the runner immediately when the job is executing in THIS
     * JVM, so the common single-pod case does not wait out the 60s lease-heartbeat sweep.
     */
    private void notifyLocalRunner(long jobId) {
        SyncJobHandle handle = activeHandles.get(jobId);
        if (handle != null) {
            handle.refreshCancellation(true);
        }
    }

    /**
     * Touches {@code heartbeat_at} for every job currently executing in this JVM, and refreshes each
     * handle's {@code cancelRequested} flag from the database in the same pass — the cross-pod path for
     * a cancel request landing on a different pod than the one executing the job.
     */
    @Scheduled(fixedDelay = 60, initialDelay = 60, timeUnit = TimeUnit.SECONDS)
    @WorkspaceAgnostic("Refreshes only leases registered in this JVM; job ids are globally unique")
    public void refreshLeases() {
        if (activeHandles.isEmpty()) {
            return;
        }
        List<Long> ids = List.copyOf(activeHandles.keySet());
        try {
            transactionTemplate.executeWithoutResult(status -> {
                syncJobRepository.touchHeartbeat(ids);
                for (SyncJobRepository.CancelFlagProjection projection : syncJobRepository.findCancelFlags(ids)) {
                    SyncJobHandle handle = activeHandles.get(projection.getId());
                    if (handle != null) {
                        handle.refreshCancellation(projection.isCancelRequested());
                    }
                }
            });
        } catch (Exception e) {
            // Visible, not silent: a stalled heartbeat risks the zombie sweep reaping a healthy job.
            log.warn("Sync job lease heartbeat failed for {} job(s): {}", ids.size(), e.toString());
        }
    }

    /**
     * Full cross-workspace sweep, called by {@link SyncJobZombieSweeper} on startup and hourly.
     *
     * @return number of jobs reaped
     */
    public int reapAbandonedJobs() {
        Integer reaped = transactionTemplate.execute(
                status -> reapAbandoned(syncJobRepository.findAbandoned(leaseTtlSeconds())));
        return reaped == null ? 0 : reaped;
    }

    private void reapAbandonedForConnection(long connectionId) {
        transactionTemplate.executeWithoutResult(
                status -> reapAbandoned(syncJobRepository.findAbandonedForConnection(connectionId, leaseTtlSeconds())));
    }

    private int reapAbandoned(List<SyncJob> stale) {
        if (stale.isEmpty()) {
            return 0;
        }
        int reaped = 0;
        for (SyncJob job : stale) {
            if (activeHandles.containsKey(job.getId())) {
                // A live runner in THIS JVM still owns the job, so it is not abandoned — a stale lease
                // here only means the heartbeat scheduler was briefly starved, never that the work died.
                // Leave the row ACTIVE: the runner finishes on its own and connection teardown stays
                // fenced on it (ConnectionService's lifecycle lock, not this sweep). Reaping it here would
                // kill a healthy sync — e.g. a "Sync now" click running the inline reap first.
                // Connection teardown does not deadlock behind this: ConnectionService's disconnect
                // fence requests cancellation and returns a retryable 409 (see
                // {@link #requestCancelForTeardown}), and PURGE / vendor uninstall are not fenced at all.
                continue;
            }
            int updated = syncJobRepository.markAbandoned(
                    job.getId(),
                    "Abandoned. The job sent no heartbeat, probably because the pod restarted.",
                    leaseTtlSeconds());
            if (updated == 0) {
                continue;
            }
            reaped++;
            publish(
                    job.getWorkspace().getId(),
                    job.getConnection().getId(),
                    job.getKind(),
                    SyncStateChangedEvent.Scope.JOB);
        }
        // Only page when work was actually reaped: every candidate can be locally-owned or
        // heartbeat-refreshed, leaving reaped==0, and a WARN there would be a false alert
        // (mirrors SyncJobZombieSweeper.sweep's guard).
        if (reaped > 0) {
            log.warn("Reaped {} abandoned sync job(s)", reaped);
        }
        return reaped;
    }

    private static long leaseTtlSeconds() {
        return ABANDON_THRESHOLD.toSeconds();
    }

    @Override
    public void start() {
        running.set(true);
    }

    @Override
    public void stop() {
        stop(() -> {});
    }

    /**
     * Requests cancellation of every job this JVM is running — in memory, so a runner polling
     * {@link SyncJobHandle#isCancellationRequested()} aborts promptly, and durably in
     * {@code cancel_requested}, so the request survives the process and outlives this pod.
     *
     * <p>It does <em>not</em> report an outcome on the runner's behalf, and it does not interrupt
     * anyone. Consequences, accepted deliberately:
     *
     * <ul>
     *   <li>A runner that notices the flag aborts cooperatively and writes its own CANCELLED row.
     *   <li>A runner the JVM exits from under leaves its row RUNNING. That row is honest — the work
     *       stopped in an unknown place — and {@link SyncJobZombieSweeper}'s startup sweep reaps it
     *       once the lease expires. Faking CANCELLED here would claim a clean abort we never observed,
     *       and could stamp CANCELLED over a sync that had already finished successfully.
     * </ul>
     *
     * <p>This returns without draining {@link #activeHandles}; the drain budget is
     * {@code syncJobExecutor}'s {@code awaitTerminationSeconds}, at the next-lower lifecycle phase.
     */
    @Override
    public void stop(Runnable callback) {
        try {
            if (!running.compareAndSet(true, false)) {
                return;
            }
            List<Long> jobIds = List.copyOf(activeHandles.keySet());
            if (jobIds.isEmpty()) {
                return;
            }
            log.info("Requesting cancellation of {} in-flight sync job(s) for application shutdown", jobIds.size());
            for (Long jobId : jobIds) {
                notifyLocalRunner(jobId);
            }
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    for (Long jobId : jobIds) {
                        syncJobRepository.markCancelRequested(jobId, SyncJobStatus.ACTIVE);
                    }
                });
            } catch (Exception e) {
                log.warn("Could not persist sync-job shutdown cancellation: {}", e.toString());
            }
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return ExecutorConfigurationSupport.DEFAULT_PHASE + 1;
    }

    private record MarkRunningResult(boolean started, boolean cancelRequested) {}

    private MarkRunningResult markRunning(long jobId) {
        MarkRunningResult result = transactionTemplate.execute(status -> {
            if (syncJobRepository.markRunning(jobId) == 0) {
                return new MarkRunningResult(false, false);
            }
            boolean cancelRequested = syncJobRepository.findCancelFlags(List.of(jobId)).stream()
                    .findFirst()
                    .map(SyncJobRepository.CancelFlagProjection::isCancelRequested)
                    .orElse(false);
            return new MarkRunningResult(true, cancelRequested);
        });
        return result == null ? new MarkRunningResult(false, false) : result;
    }

    /**
     * Writes {@code status} as the job's terminal outcome, compare-and-set against the ACTIVE statuses
     * so a late writer can never overwrite a row that already finished.
     *
     * <p>Deliberately does <em>not</em> consult {@code cancel_requested}: per {@link #executeBody}'s one
     * rule, the caller has already folded the runner's own {@link SyncJobHandle#reportCancelled()} into
     * {@code status}, and a cancel request that the runner never honored must not rewrite the outcome
     * it did report.
     */
    private void completeJob(long jobId, SyncJobStatus status, @Nullable String errorSummary, SyncJobHandle handle) {
        transactionTemplate.executeWithoutResult(txStatus -> {
            int updated = syncJobRepository.completeActiveJob(
                    jobId,
                    status,
                    errorSummary,
                    handle.currentItemsProcessed(),
                    handle.currentItemsTotal(),
                    handle.currentProgressDetail(),
                    SyncJobStatus.ACTIVE);
            if (updated == 0) {
                log.warn("Sync job {} was no longer active at completion (attempted {})", jobId, status);
                return;
            }
            SyncJob job = syncJobRepository.findById(jobId).orElse(null);
            if (job == null) {
                return;
            }
            publish(
                    job.getWorkspace().getId(),
                    job.getConnection().getId(),
                    job.getKind(),
                    SyncStateChangedEvent.Scope.JOB);
            publish(
                    job.getWorkspace().getId(),
                    job.getConnection().getId(),
                    job.getKind(),
                    SyncStateChangedEvent.Scope.RESOURCES);
        });
    }

    private void persistProgress(
            long jobId,
            @Nullable Integer itemsProcessed,
            @Nullable Integer itemsTotal,
            Map<String, Object> progressDetail) {
        transactionTemplate.executeWithoutResult(status -> syncJobRepository
                .findById(jobId)
                .ifPresent(job -> {
                    job.setItemsProcessed(itemsProcessed);
                    job.setItemsTotal(itemsTotal);
                    if (!progressDetail.isEmpty()) {
                        job.setProgress(progressDetail);
                    }
                    syncJobRepository.save(job);
                    publish(
                            job.getWorkspace().getId(),
                            job.getConnection().getId(),
                            job.getKind(),
                            SyncStateChangedEvent.Scope.JOB);
                    // Publish RESOURCES on every progress tick, not just at terminal: a running sync writes
                    // rows the whole time it runs, so a counts pane fed only by completeJob would sit still
                    // and then jump to its final numbers. The hub coalesces per-scope at 1s and this write is
                    // already throttled, so the extra hint costs one refetch of a small DTO per tick per
                    // watching admin.
                    publish(
                            job.getWorkspace().getId(),
                            job.getConnection().getId(),
                            job.getKind(),
                            SyncStateChangedEvent.Scope.RESOURCES);
                }));
    }

    /**
     * Trailing edge of {@link SyncJobHandle}'s write throttle, for every job whose body is running in
     * this JVM.
     *
     * <p>Runs on the existing handle registry rather than a per-handle timer: the registry is already
     * the set of locally-owned jobs, already swept for lease heartbeats, and a flush is a no-op for a
     * handle with nothing buffered. A 1s tick against the handle's 2s interval means a suppressed
     * update lands 2–3s after it was reported instead of waiting for the runner's next call — which,
     * once a runner goes quiet after a phase boundary, could otherwise be minutes.
     */
    @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = TimeUnit.SECONDS)
    @WorkspaceAgnostic("Flushes only progress buffered by handles registered in this JVM")
    public void flushBufferedProgress() {
        if (activeHandles.isEmpty()) {
            return;
        }
        for (SyncJobHandle handle : List.copyOf(activeHandles.values())) {
            try {
                handle.flushIfDue();
            } catch (Exception e) {
                // A progress write is an observability nicety; it must never take down the sweep and
                // stall every other job's flush behind it.
                log.debug("Trailing progress flush failed: {}", e.toString());
            }
        }
    }

    private void pruneRetention(long connectionId) {
        try {
            transactionTemplate.executeWithoutResult(
                    status -> syncJobRepository.pruneOldJobs(connectionId, RETENTION_LIMIT));
        } catch (Exception e) {
            log.warn("Sync job retention prune failed for connection {}: {}", connectionId, e.toString());
        }
    }

    private void publish(long workspaceId, long connectionId, IntegrationKind kind, SyncStateChangedEvent.Scope scope) {
        eventPublisher.publishEvent(new SyncStateChangedEvent(workspaceId, connectionId, kind, scope));
    }

    /**
     * Builds the {@code error_summary} value. The message is vendor-controlled and this string is
     * persisted, then rendered to admins and echoed into logs — so it goes through
     * {@link LoggingUtils#sanitizeForLog} to strip control characters and ANSI escapes rather than
     * trusting the vendor not to embed them.
     */
    private static String summarize(Exception e) {
        String message = e.getMessage();
        return (e.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + LoggingUtils.sanitizeForLog(message)));
    }

    private static String truncate(String s) {
        return s.length() <= ERROR_SUMMARY_MAX_LENGTH ? s : s.substring(0, ERROR_SUMMARY_MAX_LENGTH);
    }
}
