package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.handler.JobTypeHandlerRegistry;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionResultParser;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ExistingDeliveryLookup;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobTypeHandler;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxManager;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.hub.WorkerJobCancelDispatcher;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchRepository;
import java.time.Duration;
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

/** Post-submission lifecycle operations on an agent job: user cancel and delivery retry. */
@Service
public class AgentJobLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(AgentJobLifecycleService.class);

    private static final Set<AgentJobStatus> ACTIVE_STATUSES = Set.of(AgentJobStatus.QUEUED, AgentJobStatus.RUNNING);

    private final AgentJobRepository agentJobRepository;
    private final JobTypeHandlerRegistry handlerRegistry;
    private final TransactionTemplate transactionTemplate;
    private final @Nullable SandboxManager sandboxManager;
    private final Optional<WorkerJobCancelDispatcher> workerJobCancelDispatcher;
    private final LlmUsageRecorder usageRecorder;
    private final ObjectMapper objectMapper;
    private final FeedbackDispatchRepository feedbackDispatchRepository;
    private final AgentJobTelemetry jobTelemetry;

    public AgentJobLifecycleService(
            AgentJobRepository agentJobRepository,
            JobTypeHandlerRegistry handlerRegistry,
            TransactionTemplate transactionTemplate,
            @Nullable SandboxManager sandboxManager,
            Optional<WorkerJobCancelDispatcher> workerJobCancelDispatcher,
            LlmUsageRecorder usageRecorder,
            ObjectMapper objectMapper,
            FeedbackDispatchRepository feedbackDispatchRepository,
            AgentJobTelemetry jobTelemetry) {
        this.agentJobRepository = agentJobRepository;
        this.handlerRegistry = handlerRegistry;
        this.transactionTemplate = transactionTemplate;
        this.sandboxManager = sandboxManager;
        this.workerJobCancelDispatcher = workerJobCancelDispatcher;
        this.usageRecorder = usageRecorder;
        this.objectMapper = objectMapper;
        this.feedbackDispatchRepository = feedbackDispatchRepository;
        this.jobTelemetry = jobTelemetry;
    }

    /**
     * Retries delivery for a completed job whose delivery status is FAILED. Only {@code FAILED} is
     * accepted as the CAS source: admitting {@code PENDING} would let two concurrent retries both
     * succeed, since a {@code PENDING → PENDING} no-op still reports one row updated. A job stuck at
     * PENDING is therefore not recoverable here — {@link #recoverStuckDelivery} is its path.
     */
    public AgentJob retryDelivery(Long workspaceId, UUID jobId) {
        int updated = transactionTemplate.execute(status -> {
            AgentJob job = requireJob(workspaceId, jobId);
            int transitioned = agentJobRepository.transitionDeliveryStatus(
                    jobId, DeliveryStatus.PENDING, Set.of(DeliveryStatus.FAILED));
            if (transitioned == 1) {
                int reset = feedbackDispatchRepository.resetFailedAutomaticPackage(jobId, workspaceId);
                if (reset == 0
                        && FeedbackCompositionResultParser.compositionStatus(job.getOutput())
                                .failed()) {
                    throw new AgentJobStateConflictException("Delivery cannot retry feedback that was not composed.");
                }
            }
            return transitioned;
        });

        if (updated == 0) {
            throw new AgentJobStateConflictException(
                    "You can retry delivery only for a completed review whose delivery failed.");
        }

        // Reload after the CAS commit so the entity is not stale.
        AgentJob job = transactionTemplate.execute(status -> agentJobRepository
                .findById(jobId)
                .orElseThrow(() -> new EntityNotFoundException("AgentJob", jobId.toString())));

        // Delivery leaves the database (GitHub/GitLab), so it must not run inside a transaction.
        JobTypeHandler handler = handlerRegistry.getHandler(job.getJobType());
        Instant deliveryStarted = Instant.now();
        try {
            handler.deliver(job);
            transactionTemplate.executeWithoutResult(tx -> agentJobRepository.updateDeliveryStatus(
                    jobId, completedProcessingStatus(job), job.getDeliveryCommentId()));
            log.info("Delivery retry succeeded: jobId={}", jobId);
            jobTelemetry.transition(
                    job,
                    "agent.job.delivery",
                    AgentJobTelemetry.Phase.DELIVERY,
                    completedProcessingStatus(job) == DeliveryStatus.FAILED
                            ? AgentJobTelemetry.Outcome.DELIVERY_FAILED
                            : AgentJobTelemetry.Outcome.DELIVERED,
                    Duration.between(deliveryStarted, Instant.now()));
        } catch (Exception e) {
            transactionTemplate.executeWithoutResult(tx ->
                    agentJobRepository.updateDeliveryStatus(jobId, DeliveryStatus.FAILED, job.getDeliveryCommentId()));
            jobTelemetry.transition(
                    job,
                    "agent.job.delivery",
                    AgentJobTelemetry.Phase.DELIVERY,
                    AgentJobTelemetry.Outcome.DELIVERY_FAILED,
                    Duration.between(deliveryStarted, Instant.now()));
            log.warn("Delivery retry failed: jobId={}, error={}", jobId, e.getMessage(), e);
            throw new AgentJobStateConflictException("We could not post the feedback. Try again in a moment.", e);
        }

        return transactionTemplate.execute(status -> requireJob(workspaceId, jobId));
    }

    private record CancelOutcome(AgentJob job, boolean dispatchCancel) {}

    /**
     * Idempotent when the job is already CANCELLED; throws 409 for any other terminal state.
     *
     * <p>The ledger write runs INSIDE the status-transition transaction ({@link LlmUsageRecorder}'s
     * append is {@code MANDATORY}) so accounting and the state change stand or fall together. Only the
     * sandbox cancel runs after the commit, because it leaves the database.
     */
    public AgentJob cancel(Long workspaceId, UUID jobId) {
        CancelOutcome outcome = transactionTemplate.execute(status -> cancelTransition(workspaceId, jobId));

        // Split deployment: ask the owning worker to stop its container over the WSS channel (ADR 0009).
        // No-op if that worker isn't connected here — the DB transition + backstops still finish the cancel.
        if (outcome.dispatchCancel()) {
            String workerId = outcome.job().getWorkerId();
            if (workerId != null) {
                workerJobCancelDispatcher.ifPresent(d -> d.dispatch(workerId, jobId, "user-cancel"));
            }

            // Monolith / co-located worker: stop the container in-process.
            if (sandboxManager != null) {
                try {
                    sandboxManager.cancel(jobId);
                } catch (Exception e) {
                    log.warn("Sandbox cancel failed for job {} (status already CANCELLED): {}", jobId, e.getMessage());
                }
            }
        }

        return outcome.job();
    }

    private AgentJob requireJob(Long workspaceId, UUID jobId) {
        return agentJobRepository
                .findByIdAndWorkspaceId(jobId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("AgentJob", jobId.toString()));
    }

    private int casToCancelled(UUID jobId) {
        return agentJobRepository.transitionStatus(
                jobId, AgentJobStatus.CANCELLED, Instant.now(), null, ACTIVE_STATUSES);
    }

    /** The status-transition CAS + races, run inside {@link #transactionTemplate}. */
    private CancelOutcome cancelTransition(Long workspaceId, UUID jobId) {
        AgentJob job = requireJob(workspaceId, jobId);

        if (job.getStatus() == AgentJobStatus.CANCELLED) {
            return new CancelOutcome(job, false);
        }

        if (job.getStatus().isTerminal()) {
            throw new AgentJobStateConflictException("This review already finished. You cannot cancel it.");
        }

        if (casToCancelled(jobId) == 0) {
            AgentJob raced = requireJob(workspaceId, jobId);
            if (raced.getStatus().isTerminal()) {
                throw new AgentJobStateConflictException("This review finished before the server could cancel it.");
            }
            // Back inside the CAS window, so a concurrent claim moved it there; retry once and then
            // report whatever state the loser observes rather than spinning against the executor.
            if (casToCancelled(jobId) == 0) {
                AgentJob racedAgain = requireJob(workspaceId, jobId);
                if (racedAgain.getStatus() != AgentJobStatus.CANCELLED) {
                    throw new AgentJobStateConflictException(
                            "This review changed state while the server canceled it. Reload the review and try again.");
                }
                return new CancelOutcome(racedAgain, false);
            }
        }

        // Reload to read worker_id, which is only set at claim.
        AgentJob fresh = requireJob(workspaceId, jobId);
        recordUnverifiableUsageIfStarted(workspaceId, fresh);
        jobTelemetry.terminal(fresh, AgentJobStatus.CANCELLED, AgentJobTelemetry.age(fresh));
        return new CancelOutcome(fresh, true);
    }

    /**
     * Appends the attempt's ledger rows only once the job crossed {@code execution_started_at}. A non-null
     * {@code worker_id} proves only that a worker claimed the row, and preparation before that boundary
     * cannot incur provider usage — booking it would make the workspace's month unverifiable for free.
     */
    private void recordUnverifiableUsageIfStarted(Long workspaceId, AgentJob job) {
        if (job.getExecutionStartedAt() == null) {
            return;
        }
        ConfigSnapshot snap = ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper);
        LlmPriceSnapshot price =
                snap.priceSnapshot() != null ? snap.priceSnapshot() : LlmPriceSnapshot.unpricedInstance();
        // A cancel from the API cannot see what the attempt consumed; the executor path that observes
        // the cancellation bills the proxy-attributed tokens, so the review's own row carries none. A
        // precompute call waits for the job row and is dropped once the transition above ends the
        // attempt, so the precompute rows are complete here.
        boolean billed =
                TerminalUsage.none().appendTo(usageRecorder, agentJobRepository, workspaceId, job, snap, price);
        log.info(
                "Recorded {} usage ledger entry (user-cancel): jobId={}",
                billed ? "a confirmed" : "an UNPRICED",
                job.getId());
    }

    /**
     * Re-delivers a job stuck at {@code delivery_status=PENDING} because the executor crashed between
     * the terminal write and the delivery itself. The caller has already won
     * {@link AgentJobRepository#claimDeliveryRecoveryAttempt}, so no claim happens here.
     *
     * <p>The crash may have happened after the comment posted but before {@code deliveryCommentId} was
     * persisted, so the handler is asked first whether a delivery already landed. An {@code UNKNOWN}
     * answer does not post: that would risk exactly the duplicate this check exists to prevent. A handler
     * that reconciles its own delivery state is not asked; its delivery settles what it may already have made.
     *
     * @param claimedAttempts the post-increment {@code delivery_attempts} this call's CAS just wrote,
     *     which fences its terminal write (see {@link
     *     AgentJobRepository#transitionDeliveryStatusFenced})
     * @return true if this attempt settled processing; incomplete composition remains FAILED.
     *     A false return leaves recovery to its existing state and attempt fences.
     */
    boolean recoverStuckDelivery(AgentJob job, short claimedAttempts) {
        JobTypeHandler handler = handlerRegistry.getHandler(job.getJobType());
        if (handler.reconcilesDeliveryState()) {
            return redeliver(handler, job, claimedAttempts);
        }

        ExistingDeliveryLookup existing;
        try {
            existing = handler.findExistingDelivery(job);
        } catch (RuntimeException e) {
            log.debug(
                    "Existing-delivery dedup check failed (treated as unknown): jobId={}, error={}",
                    job.getId(),
                    e.getMessage());
            existing = ExistingDeliveryLookup.unknown();
        }

        if (existing.kind() == ExistingDeliveryLookup.Kind.UNKNOWN) {
            log.debug(
                    "Existing-delivery dedup check was inconclusive — leaving PENDING rather than risking a "
                            + "duplicate post: jobId={}",
                    job.getId());
            return false;
        }

        if (existing.kind() == ExistingDeliveryLookup.Kind.FOUND) {
            String existingCommentId = existing.commentId();
            boolean won = fencedDeliveryWrite(
                    job.getId(), completedProcessingStatus(job), existingCommentId, claimedAttempts);
            if (won) {
                log.info(
                        "Delivery recovery found an already-posted comment (crash before recording) — not re-posting: jobId={}, commentId={}",
                        job.getId(),
                        existingCommentId);
            }
            return won;
        }
        return redeliver(handler, job, claimedAttempts);
    }

    private boolean redeliver(JobTypeHandler handler, AgentJob job, short claimedAttempts) {
        try {
            handler.deliver(job);
            boolean won = fencedDeliveryWrite(
                    job.getId(), completedProcessingStatus(job), job.getDeliveryCommentId(), claimedAttempts);
            if (won) {
                log.info("Delivery recovery succeeded: jobId={}", job.getId());
            }
            return won;
        } catch (Exception e) {
            // No terminal write: leaving PENDING lets a later sweep pass retry. The sweeper writes the
            // terminal FAILED once it observes the attempt cap exhausted.
            log.warn("Delivery recovery attempt failed: jobId={}, error={}", job.getId(), e.getMessage());
            return false;
        }
    }

    private static DeliveryStatus completedProcessingStatus(AgentJob job) {
        return FeedbackCompositionResultParser.compositionStatus(job.getOutput())
                        .failed()
                ? DeliveryStatus.FAILED
                : DeliveryStatus.DELIVERED;
    }

    /** Logs rather than throws when the fence is lost: a superseded attempt is expected to no-op. */
    private boolean fencedDeliveryWrite(
            UUID jobId, DeliveryStatus newStatus, @Nullable String commentId, short claimedAttempts) {
        Integer updated = transactionTemplate.execute(tx -> agentJobRepository.transitionDeliveryStatusFenced(
                jobId, newStatus, commentId, Set.of(DeliveryStatus.PENDING), claimedAttempts));
        boolean won = updated != null && updated > 0;
        if (!won) {
            log.info(
                    "Delivery-recovery terminal write superseded by a later attempt — leaving current state: jobId={}, attemptedStatus={}",
                    jobId,
                    newStatus);
        }
        return won;
    }
}
