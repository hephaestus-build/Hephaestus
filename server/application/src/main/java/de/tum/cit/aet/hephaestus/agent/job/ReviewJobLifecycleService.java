package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxManager;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.hub.WorkerJobCancelDispatcher;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReviewJobLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(ReviewJobLifecycleService.class);

    private static final Set<ReviewJobStatus> ACTIVE_STATUSES = Set.of(ReviewJobStatus.QUEUED, ReviewJobStatus.RUNNING);

    private final ReviewJobRepository jobRepository;
    private final TransactionTemplate transactionTemplate;
    private final @Nullable SandboxManager sandboxManager;
    private final Optional<WorkerJobCancelDispatcher> workerJobCancelDispatcher;
    private final ReviewAttemptUsageService attemptUsage;
    private final ObjectMapper objectMapper;
    private final ReviewJobTelemetry jobTelemetry;

    public ReviewJobLifecycleService(
            ReviewJobRepository jobRepository,
            TransactionTemplate transactionTemplate,
            @Nullable SandboxManager sandboxManager,
            Optional<WorkerJobCancelDispatcher> workerJobCancelDispatcher,
            ReviewAttemptUsageService attemptUsage,
            ObjectMapper objectMapper,
            ReviewJobTelemetry jobTelemetry) {
        this.jobRepository = jobRepository;
        this.transactionTemplate = transactionTemplate;
        this.sandboxManager = sandboxManager;
        this.workerJobCancelDispatcher = workerJobCancelDispatcher;
        this.attemptUsage = attemptUsage;
        this.objectMapper = objectMapper;
        this.jobTelemetry = jobTelemetry;
    }

    private record CancelOutcome(ReviewJob job, boolean stopContainer) {}

    /**
     * Idempotent when the job is already CANCELLED; throws 409 for any other terminal state.
     *
     * <p>The ledger write runs INSIDE the status-transition transaction ({@link ReviewAttemptUsageService}'s
     * settlement is {@code MANDATORY}) so accounting and the state change stand or fall together. Only the
     * sandbox cancel runs after the commit, because it leaves the database.
     */
    public ReviewJob cancel(Long workspaceId, UUID jobId) {
        CancelOutcome outcome = transactionTemplate.execute(status -> cancelTransition(workspaceId, jobId));

        // Split deployment: ask the owning worker to stop its container over the WSS channel (ADR 0009).
        // No-op if that worker isn't connected here — the DB transition + backstops still finish the cancel.
        if (outcome.stopContainer()) {
            String workerId = outcome.job().getWorkerId();
            if (workerId != null) {
                workerJobCancelDispatcher.ifPresent(d -> d.sendCancel(workerId, jobId, "user-cancel"));
            }

            // Monolith / co-located worker: stop the container in-process.
            if (sandboxManager != null) {
                try {
                    sandboxManager.cancel(jobId);
                } catch (Exception e) {
                    log.warn(
                            "Sandbox cancel failed for job {} (status already CANCELLED): errorType={}",
                            jobId,
                            e.getClass().getName());
                }
            }
        }

        return outcome.job();
    }

    private ReviewJob requireJob(Long workspaceId, UUID jobId) {
        return jobRepository
                .findByIdAndWorkspaceId(jobId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("ReviewJob", jobId.toString()));
    }

    private int casToCancelled(UUID jobId) {
        return jobRepository.transitionToCancelled(
                jobId, Instant.now(), null, ReviewJobCancellationReason.CANCELLED_BY_REQUESTER, ACTIVE_STATUSES);
    }

    private CancelOutcome cancelTransition(Long workspaceId, UUID jobId) {
        ReviewJob job = requireJob(workspaceId, jobId);

        if (job.getStatus() == ReviewJobStatus.CANCELLED) {
            return new CancelOutcome(job, false);
        }

        if (!ACTIVE_STATUSES.contains(job.getStatus())) {
            throw new ReviewJobStateConflictException("This review already finished. You cannot cancel it.");
        }

        if (casToCancelled(jobId) == 0) {
            ReviewJob raced = requireJob(workspaceId, jobId);
            if (!ACTIVE_STATUSES.contains(raced.getStatus())) {
                throw new ReviewJobStateConflictException("This review finished before the server could cancel it.");
            }
            // Back inside the CAS window, so a concurrent claim moved it there; retry once and then
            // report whatever state the loser observes rather than spinning against the executor.
            if (casToCancelled(jobId) == 0) {
                ReviewJob racedAgain = requireJob(workspaceId, jobId);
                if (racedAgain.getStatus() != ReviewJobStatus.CANCELLED) {
                    throw new ReviewJobStateConflictException(
                            "This review changed state while the server canceled it. Reload the review and try again.");
                }
                return new CancelOutcome(racedAgain, false);
            }
        }

        // Reload to read worker_id, which is only set at claim.
        ReviewJob fresh = requireJob(workspaceId, jobId);
        recordUsageIfStarted(fresh);
        jobTelemetry.terminal(fresh, ReviewJobStatus.CANCELLED, ReviewJobTelemetry.age(fresh));
        return new CancelOutcome(fresh, true);
    }

    // A claim alone proves no provider usage; accounting begins at execution_started_at.
    private void recordUsageIfStarted(ReviewJob job) {
        if (job.getExecutionStartedAt() == null) {
            return;
        }
        ConfigSnapshot snap = ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper);
        LlmPriceSnapshot price = snap.model().priceSnapshot() != null
                ? snap.model().priceSnapshot()
                : LlmPriceSnapshot.unpricedInstance();
        // The terminal update holds the row lock, so a call that returns now settles after this, as a late call.
        // Calls without token evidence stay unknown. No calls add no ledger row.
        boolean billed = attemptUsage
                .settleAttempt(
                        job,
                        FrozenCharge.admitted(snap, price),
                        null,
                        jobRepository.findLlmUsageById(job.getId()).orElse(null))
                .billed();
        log.info("Settled job usage (user-cancel): priced={}, jobId={}", billed, job.getId());
    }
}
