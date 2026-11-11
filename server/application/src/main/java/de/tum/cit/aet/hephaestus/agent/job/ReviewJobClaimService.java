package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelUnavailableException;
import de.tum.cit.aet.hephaestus.agent.composer.CompositionStop;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.proxy.LlmCallPermits;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunHold;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunLease;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetBlockReason;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStart;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnWorkerRole
@ConditionalOnBooleanProperty(RuntimeRole.AGENT_ENABLED_PROPERTY)
@WorkspaceAgnostic("The claim leases the next review run of every workspace; the run carries its workspace")
public class ReviewJobClaimService {
    private static final Logger log = LoggerFactory.getLogger(ReviewJobClaimService.class);
    private static final Duration BUDGET_HOLD_INTERVAL = Duration.ofHours(1);
    private static final Duration BUDGET_HOLD_MAX_JOB_AGE = Duration.ofDays(7);
    /** How long a run waits when a lifecycle write or its model pool held its claim. */
    private static final Duration LOCKED_RETRY = Duration.ofSeconds(2);

    private final ReviewJobRepository jobRepository;
    private final ReviewMemberAiPolicy memberAiPolicy;
    private final ReviewMethodVerifier methods;
    private final ReviewJobAssignments assignments;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final ReviewStart reviewStart;
    private final LlmBudgetService llmBudgetService;
    private final @Nullable LlmAdmissionService llmAdmissionService;
    private final ReviewJobTelemetry jobTelemetry;
    private final LlmCallPermits callPermits;
    private final AgentRuns runs;
    private final ReviewRunKind reviewRuns;

    public ReviewJobClaimService(
            ReviewJobRepository jobRepository,
            ReviewMemberAiPolicy memberAiPolicy,
            ReviewMethodVerifier methods,
            ReviewJobAssignments assignments,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            ReviewStart reviewStart,
            LlmBudgetService llmBudgetService,
            @Nullable LlmAdmissionService llmAdmissionService,
            ReviewJobTelemetry jobTelemetry,
            LlmCallPermits callPermits,
            AgentRuns runs,
            ReviewRunKind reviewRuns) {
        this.jobRepository = jobRepository;
        this.runs = runs;
        this.reviewRuns = reviewRuns;
        this.callPermits = callPermits;
        this.memberAiPolicy = memberAiPolicy;
        this.methods = methods;
        this.assignments = assignments;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.reviewStart = reviewStart;
        this.llmBudgetService = llmBudgetService;
        this.llmAdmissionService = llmAdmissionService;
        this.jobTelemetry = jobTelemetry;
    }

    sealed interface ClaimAttempt {}

    /** The run's model pool has background callers waiting; the claim was rolled back, and this poll skips the run. */
    record PoolWait(UUID runId, long poolId) implements ClaimAttempt {}

    /** The claim was rolled back, and this poll skips the run. */
    record Declined(UUID runId, ClaimOutcome why) implements ClaimAttempt {}

    enum ClaimOutcome implements ClaimAttempt {
        /** No review run is offered now. */
        NOTHING,
        /** Its review or job is held by a lifecycle write. */
        LOCKED,
        QUOTA_FULL,
        BUDGET_HELD,
    }

    /**
     * A claimed attempt.
     *
     * @param withholdOnly for an admitted review handed on to compose, the cause a claim check met: the attempt makes no
     *     model call and withholds what is still open with it
     */
    public record ClaimResult(
            ReviewJob job,
            AgentRunFence fence,
            ConfigSnapshot snapshot,
            String itemKind,
            @Nullable CompositionStop withholdOnly)
            implements ClaimAttempt {
        ClaimResult(ReviewJob job, AgentRunFence fence, ConfigSnapshot snapshot, String itemKind) {
            this(job, fence, snapshot, itemKind, null);
        }
    }

    record TerminalClaim(ReviewJob job, ReviewJobStatus status) implements ClaimAttempt {}

    /**
     * Leases the next review run that the claim order offers, and starts its job or settles it. A claim that cannot
     * start now rolls back, so its run keeps its epoch and its place.
     *
     * @param declined the runs this poll already declined
     */
    ClaimAttempt claimNext(Set<UUID> declined, BooleanSupplier reviewRoom) {
        var heldForBudget = new AtomicReference<@Nullable AgentRunRef>();
        ClaimAttempt attempt = Objects.requireNonNull(transactionTemplate.execute(status -> {
            AgentRunLease lease =
                    runs.claim(Set.of(AgentRunKind.REVIEW), declined).orElse(null);
            if (lease == null) return ClaimOutcome.NOTHING;
            // The review service locks the parent before it changes the review, so the claim takes the parent before
            // the job. Both skip a locked row, so a claim never waits behind a lifecycle write that holds them.
            Optional<ReviewJob> locked =
                    jobRepository.lockParentForQueuedClaim(lease.runId()).isEmpty()
                            ? Optional.empty()
                            : jobRepository.lockQueuedByRun(lease.runId(), lease.workspaceId());
            if (locked.isEmpty()) {
                status.setRollbackOnly();
                return new Declined(lease.runId(), ClaimOutcome.LOCKED);
            }
            ReviewJob job = locked.get();
            AgentRunFence fence = lease.fence();
            ClaimAttempt claimed;
            try {
                claimed = claimLocked(job, fence, reviewRoom);
            } catch (JobPreparationException failure) {
                return failPreparation(job, fence, failure);
            }
            switch (claimed) {
                case TerminalClaim ended -> runs.end(fence);
                case PoolWait waiting -> status.setRollbackOnly();
                case ClaimOutcome.QUOTA_FULL -> {
                    status.setRollbackOnly();
                    return new Declined(lease.runId(), ClaimOutcome.QUOTA_FULL);
                }
                case ClaimOutcome.BUDGET_HELD -> {
                    // A hold runs nothing, so it takes no attempt: the claim rolls back and the hold is written apart.
                    status.setRollbackOnly();
                    heldForBudget.set(new AgentRunRef(lease.runId(), lease.workspaceId()));
                }
                default -> {}
            }
            return claimed;
        }));
        AgentRunRef held = heldForBudget.get();
        if (held != null) holdForBudget(held);
        return attempt;
    }

    /**
     * Starts the job of the run that its sandbox just leased under {@code fence}, in the caller's transaction: the same checks as a claim of
     * the worker's, but no room or quota check, since the sandbox already exists. A claim that cannot start now leaves
     * nothing leased: a refused job ends with its run, and one that must wait goes back to the queue with its run,
     * spending no attempt.
     *
     * @return the started attempt; empty when its run was ended or requeued instead
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ClaimResult> claimLeased(AgentRunFence fence) {
        Optional<ReviewJob> locked =
                jobRepository.lockParentForQueuedClaim(fence.runId()).isEmpty()
                        ? Optional.empty()
                        : jobRepository.lockQueuedByRun(fence.runId(), fence.workspaceId());
        if (locked.isEmpty()) {
            // A lifecycle write holds the review or its job; the run waits for the next pull.
            runs.requeue(fence, AgentRuns.Charge.FREE, LOCKED_RETRY, null);
            return Optional.empty();
        }
        ReviewJob job = locked.get();
        ClaimAttempt claimed;
        try {
            claimed = claimLocked(job, fence, null);
        } catch (JobPreparationException failure) {
            failPreparation(job, fence, failure);
            return Optional.empty();
        }
        return switch (claimed) {
            case ClaimResult started -> Optional.of(started);
            case TerminalClaim ended -> {
                runs.end(fence);
                yield Optional.empty();
            }
            case PoolWait waiting -> {
                runs.requeue(fence, AgentRuns.Charge.FREE, LOCKED_RETRY, null);
                yield Optional.empty();
            }
            case ClaimOutcome.BUDGET_HELD -> {
                runs.requeue(fence, AgentRuns.Charge.FREE, BUDGET_HOLD_INTERVAL, AgentRunHold.BUDGET);
                yield Optional.empty();
            }
            case ClaimOutcome other -> throw new IllegalStateException("A sandbox's claim cannot be " + other);
            case Declined declined -> throw new IllegalStateException("A sandbox's claim is never declined");
        };
    }

    /**
     * Marks the queued job of {@code run} as held by the budget, so raising the cap can release it at once, and offers
     * the run again after {@link #BUDGET_HOLD_INTERVAL}. A lifecycle write that holds the job meanwhile wins; the next
     * claim checks the budget again.
     */
    private void holdForBudget(AgentRunRef run) {
        transactionTemplate.executeWithoutResult(status -> jobRepository
                .lockQueuedByRun(run.id(), run.workspaceId())
                .ifPresent(job -> runs.hold(run, AgentRunHold.BUDGET, BUDGET_HOLD_INTERVAL)));
    }

    /** Fails a job whose inputs could not be prepared, and ends its run, in the caller's transaction. */
    private TerminalClaim failPreparation(ReviewJob job, AgentRunFence fence, JobPreparationException failure) {
        PreparationFailure preparation = PreparationFailure.of(PreparationFailure.Stage.INPUTS, failure);
        job.setStatus(ReviewJobStatus.FAILED);
        job.setCompletedAt(Instant.now());
        job.setErrorMessage(preparation.message());
        jobRepository.save(job);
        var diagnosis = objectMapper
                .createObjectNode()
                .put("attempt", job.getAttempt())
                .put("stage", preparation.stage().name())
                .put("kind", preparation.kind().name())
                .put("reason", preparation.reason().name());
        runs.end(fence);
        jobRepository.recordPreparationFailure(
                job.getId(),
                job.getWorkspaceId(),
                job.getAttempt(),
                job.getRunId(),
                objectMapper.writeValueAsString(diagnosis));
        jobTelemetry.preparationFailed(job, preparation, "FAILED");
        return new TerminalClaim(job, ReviewJobStatus.FAILED);
    }

    /** @param reviewRoom whether a worker has room for one more review sandbox; null where the sandbox exists */
    private ClaimAttempt claimLocked(ReviewJob job, AgentRunFence fence, @Nullable BooleanSupplier reviewRoom) {
        int maxRetries = runs.capacity().reviewRetries();

        if (memberAiPolicy.isProcessingSuppressed(job)) {
            job.setStatus(ReviewJobStatus.CANCELLED);
            job.setCompletedAt(Instant.now());
            job.setErrorMessage("A personal-data erasure request blocks this job.");
            job.setCancellationReason(ReviewJobCancellationReason.PERSON_DATA_ERASED);
            jobRepository.save(job);
            jobTelemetry.claimRefused("person_data_erased");
            return new TerminalClaim(job, ReviewJobStatus.CANCELLED);
        }

        // Queued again after its results were admitted: a new attempt would capture the work
        // anew, and its citations would not be the ones the admission verified. Its earlier attempt
        // was billed when it was requeued, and nothing has run since. Only an attempt handed on to
        // compose is claimed. A check below that would refuse it instead claims it to withhold what
        // is still open with that cause, because a refused review would leave its destinations
        // without a reason. An erasure above cancels it: an erased person gets no new rows.
        boolean composing = ResultAdmissionService.isContinuation(job);
        if (ResultAdmissionService.isAdmitted(job) && !composing) {
            job.setStatus(ReviewJobStatus.FAILED);
            job.setCompletedAt(Instant.now());
            job.setErrorMessage(ResultAdmissionService.INTERRUPTED_AFTER_ADMISSION);
            jobRepository.save(job);
            return new TerminalClaim(job, ReviewJobStatus.FAILED);
        }

        if (!memberAiPolicy.permitsReview(job)) {
            if (composing) return claimToWithhold(job, fence, CompositionStop.NO_AI);
            job.setStatus(ReviewJobStatus.CANCELLED);
            job.setCompletedAt(Instant.now());
            job.setErrorMessage("The developer has not enabled AI practice reviews in this workspace.");
            job.setCancellationReason(ReviewJobCancellationReason.MEMBER_AI_DECLINED);
            jobRepository.save(job);
            jobTelemetry.claimRefused("member_ai_declined");
            return new TerminalClaim(job, ReviewJobStatus.CANCELLED);
        }

        if (reviewStart.replaceByNewerOccasion(job.getWorkspaceId(), job.getReviewId())) {
            job.setStatus(ReviewJobStatus.CANCELLED);
            job.setCompletedAt(Instant.now());
            job.setErrorMessage("A newer update of the work replaced this review before it started.");
            job.setCancellationReason(ReviewJobCancellationReason.REPLACED);
            jobRepository.save(job);
            jobTelemetry.claimRefused("replaced");
            return new TerminalClaim(job, ReviewJobStatus.CANCELLED);
        }

        boolean retained = assignments.hasStaged(job);
        // Past the retry limit, the run still offers a lost attempt a few times, so that a review with staged results
        // can withhold what it has open. Anything else past the limit ends here.
        if (reviewRuns.retriesExhausted(job, retained)) {
            job.setStatus(ReviewJobStatus.FAILED);
            job.setCompletedAt(Instant.now());
            if (ResultAdmissionService.isAdmitted(job))
                // Its results stay. Settlement closes the review as partial: nothing confirms each destination.
                job.setOutput(
                        objectMapper.createObjectNode().put("outcome", RefusalOutcome.COMPOSITION_UNCONFIRMED.name()));
            else job.setErrorMessage(ReviewRunKind.RETRIES_EXHAUSTED);
            jobRepository.save(job);
            return new TerminalClaim(job, ReviewJobStatus.FAILED);
        }
        if (job.spentAttempts() > maxRetries && retained)
            return claimToWithhold(job, fence, CompositionStop.RETRIES_EXHAUSTED);

        LlmBudgetBlockReason blockReason =
                llmBudgetService.decide(job.getWorkspaceId()).forFunding(claimedFundingSource(job));
        if (blockReason != LlmBudgetBlockReason.NONE) {
            if (composing || assignments.readyToSeal(job)) return claimToWithhold(job, fence, CompositionStop.BUDGET);
            return holdOrCancelOverBudget(job, blockReason);
        }

        // Live-revalidate the catalog binding immediately before RUNNING. Submit-time behaviour
        // stays frozen; only availability/grants and the price are refreshed, and a changed binding
        // is refused rather than silently switching the queued job to another model.
        WorkspaceAgentBinding binding = memberAiPolicy.binding(job).orElse(null);
        if (binding == null) {
            return composing || retained
                    ? claimToWithhold(job, fence, CompositionStop.MODEL_UNAVAILABLE)
                    : refuseUnavailableModel(job);
        }
        var assigned = assignments.load(job);
        ConfigSnapshot snapshot = admitModels(job, binding, assigned);
        if (snapshot == null)
            return composing || retained
                    ? claimToWithhold(job, fence, CompositionStop.MODEL_UNAVAILABLE)
                    : refuseUnavailableModel(job);
        String itemKind = assigned.getFirst().item().kind().value();
        if (!composing) {
            if (reviewRoom != null && !reviewRoom.getAsBoolean()) return ClaimOutcome.QUOTA_FULL;
            long poolId = callPermits.poolFor(snapshot.model().connectionRef());
            if (callPermits.hasReviewCallsWaiting(poolId)) return new PoolWait(fence.runId(), poolId);
        }

        if (!(ResultAdmissionService.isContinuation(job)
                ? reviewStart.compose(job.getWorkspaceId(), job.getReviewId())
                : reviewStart.start(job.getWorkspaceId(), job.getReviewId()))) return cancelWithParent(job);
        Instant claimedAt = Instant.now();
        // The first claim only: a requeued review would sample its submission again, later each time.
        if (fence.epoch() == 1) {
            jobTelemetry.claimWait(Duration.between(job.getSubmittedAt(), claimedAt));
        }
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setStartedAt(claimedAt);
        job.setExecutionStartedAt(null);
        job.setConfigSnapshot(snapshot.toJson(objectMapper));
        jobRepository.save(job);

        return new ClaimResult(job, fence, snapshot, itemKind);
    }

    /**
     * The review was cancelled or has ended, and its cancellation has not reached this job, for example after a
     * restart. Left queued, the job would come first in every poll and never run.
     */
    private TerminalClaim cancelWithParent(ReviewJob job) {
        job.setStatus(ReviewJobStatus.CANCELLED);
        job.setCompletedAt(Instant.now());
        job.setErrorMessage("Its review was cancelled or has ended.");
        job.setCancellationReason(ReviewJobCancellationReason.CANCELLED_BY_REQUESTER);
        jobRepository.save(job);
        return new TerminalClaim(job, ReviewJobStatus.CANCELLED);
    }

    /** Claims an attempt that makes no model call: it withholds what its review still has open, with this cause. */
    private ClaimAttempt claimToWithhold(ReviewJob job, AgentRunFence fence, CompositionStop cause) {
        String itemKind = assignments.load(job).getFirst().item().kind().value();
        if (!(ResultAdmissionService.isContinuation(job)
                ? reviewStart.compose(job.getWorkspaceId(), job.getReviewId())
                : reviewStart.start(job.getWorkspaceId(), job.getReviewId()))) return cancelWithParent(job);
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setStartedAt(Instant.now());
        job.setExecutionStartedAt(null);
        jobRepository.save(job);
        return new ClaimResult(
                job, fence, ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper), itemKind, cause);
    }

    private @Nullable ConfigSnapshot admitModels(
            ReviewJob job, WorkspaceAgentBinding binding, List<ReviewAssignmentSnapshot> assigned) {
        ConfigSnapshot snapshot;
        try {
            ConfigSnapshot submitted = ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper);
            if (Objects.requireNonNullElse(submitted.model().dataHandlingTier(), DataHandlingTier.UNDECLARED)
                    != binding.getDataHandlingTier())
                throw new ReviewJobStateConflictException("The frozen model is unavailable");
            if (llmAdmissionService != null) {
                var admitted = llmAdmissionService
                        .admitIfAvailable(binding)
                        .orElseThrow(() -> new LlmModelUnavailableException("The frozen model is unavailable"));
                methods.require(assigned.getFirst().practice().method(), "review", binding);
                if (!submitted.model().isAdmittedAs(admitted)) {
                    throw new ReviewJobStateConflictException("The frozen model is unavailable");
                }
                snapshot = submitted
                        .withPriceSnapshot(
                                submitted.model().priceSnapshot() == null
                                        ? admitted.price()
                                        : submitted.model().priceSnapshot())
                        .withPracticeScript(
                                admitPracticeScript(job, submitted.practiceScript(), llmAdmissionService, assigned));
            } else {
                methods.require(assigned.getFirst().practice().method(), "review", binding);
                snapshot = submitted;
            }
        } catch (LlmModelUnavailableException | ReviewJobStateConflictException failure) {
            return null;
        }
        return snapshot;
    }

    private @Nullable Map<ModelKind, FrozenModel> admitPracticeScript(
            ReviewJob job,
            @Nullable Map<ModelKind, FrozenModel> submitted,
            LlmAdmissionService admission,
            List<ReviewAssignmentSnapshot> assigned) {
        if (submitted == null) {
            return null;
        }
        Map<ModelKind, FrozenModel> admitted = new EnumMap<>(ModelKind.class);
        for (AgentPurpose purpose : AgentPurpose.practiceScript()) {
            FrozenModel slot = submitted.get(purpose.kind());
            if (slot == null) {
                continue;
            }
            DataHandlingTier tier = Objects.requireNonNullElse(slot.dataHandlingTier(), DataHandlingTier.UNDECLARED);
            Optional<FrozenModel> priced = memberAiPolicy
                    .practiceScriptBinding(job, purpose)
                    .filter(binding -> binding.getDataHandlingTier() == tier)
                    .flatMap(binding -> admission.admitIfAvailable(binding).map(model -> {
                        methods.require(
                                assigned.getFirst().practice().method(),
                                purpose.kind().name().toLowerCase(Locale.ROOT),
                                binding);
                        return model;
                    }))
                    .filter(slot::isAdmittedAs)
                    .map(model -> slot.withPriceSnapshot(
                            slot.priceSnapshot() == null ? model.price() : slot.priceSnapshot()));
            if (priced.isPresent()) {
                admitted.put(purpose.kind(), priced.get());
            } else {
                throw new LlmModelUnavailableException("A frozen direct model is no longer available");
            }
        }
        return admitted.isEmpty() ? null : admitted;
    }

    // Queued work can outpace budget updates, so claim must recheck the cap.
    // Hold without consuming an attempt; a month rollover or raised cap can release it.
    private ClaimAttempt holdOrCancelOverBudget(ReviewJob job, LlmBudgetBlockReason blockReason) {
        Instant now = Instant.now();
        boolean tooOldToHold = job.getSubmittedAt() != null
                && Duration.between(job.getSubmittedAt(), now).compareTo(BUDGET_HOLD_MAX_JOB_AGE) > 0;
        if (tooOldToHold) {
            job.setStatus(ReviewJobStatus.CANCELLED);
            job.setCompletedAt(now);
            job.setErrorMessage("Cancelled. The monthly AI budget is exceeded, and this job is more than "
                    + BUDGET_HOLD_MAX_JOB_AGE.toDays()
                    + " days old.");
            job.setCancellationReason(ReviewJobCancellationReason.BUDGET_EXHAUSTED);
            jobRepository.save(job);
            log.info(
                    "Cancelling claim — over budget and older than {} days: jobId={}, workspaceId={}, blockReason={}",
                    BUDGET_HOLD_MAX_JOB_AGE.toDays(),
                    job.getId(),
                    job.getWorkspaceId(),
                    blockReason);
            jobTelemetry.claimRefused("budget_exhausted");
            jobTelemetry.budgetRefused();
            return new TerminalClaim(job, ReviewJobStatus.CANCELLED);
        }
        log.info(
                "Holding claim — monthly LLM budget {}: jobId={}, workspaceId={}, retryIn={}",
                blockReason == LlmBudgetBlockReason.EXHAUSTED ? "exhausted" : "unverifiable (cap set)",
                job.getId(),
                job.getWorkspaceId(),
                BUDGET_HOLD_INTERVAL);
        jobTelemetry.budgetHeld();
        return ClaimOutcome.BUDGET_HELD;
    }

    private ClaimAttempt refuseUnavailableModel(ReviewJob job) {
        String message = "The configured model is not available.";
        job.setStatus(ReviewJobStatus.CANCELLED);
        job.setCompletedAt(Instant.now());
        job.setErrorMessage(message);
        job.setCancellationReason(ReviewJobCancellationReason.MODEL_UNAVAILABLE);
        jobRepository.save(job);
        jobTelemetry.modelRefused();
        jobTelemetry.claimRefused("model_unavailable");
        log.info("Refusing claim — configured model unavailable: jobId={}", job.getId());
        return new TerminalClaim(job, ReviewJobStatus.CANCELLED);
    }

    private @Nullable UsageFundingSource claimedFundingSource(ReviewJob job) {
        return memberAiPolicy
                .binding(job)
                .map(WorkspaceAgentBinding::getFundingSource)
                .orElse(null);
    }
}
