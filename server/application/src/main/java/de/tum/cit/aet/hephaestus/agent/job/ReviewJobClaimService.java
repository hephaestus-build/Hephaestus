package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelUnavailableException;
import de.tum.cit.aet.hephaestus.agent.composer.CompositionStop;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.proxy.LlmCallPermits;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunHold;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetBlockReason;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStart;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnAgentRuntime
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
        this.objectMapper = objectMapper;
        this.reviewStart = reviewStart;
        this.llmBudgetService = llmBudgetService;
        this.llmAdmissionService = llmAdmissionService;
        this.jobTelemetry = jobTelemetry;
    }

    sealed interface ClaimAttempt {}

    /** The run's model pool has background callers waiting, so the run waits for its next pull. */
    record PoolWait(UUID runId, long poolId) implements ClaimAttempt {}

    enum ClaimOutcome implements ClaimAttempt {
        /** The workspace's budget holds the job. */
        BUDGET_HELD
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
     * Starts the job of the run that its sandbox just leased under {@code fence}, in the caller's transaction: the same checks as a claim of
     * the server's, but no room or quota check, since the sandbox already exists. A claim that cannot start now leaves
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
        var logScope = new ReviewJobLogScope(job, fence);
        try {
            ClaimAttempt claimed;
            try {
                claimed = claimLocked(job, fence);
            } catch (JobPreparationException failure) {
                failPreparation(job, fence, failure);
                return Optional.empty();
            }
            return switch (claimed) {
                case ClaimResult started -> Optional.of(started);
                case TerminalClaim ended -> {
                    ReviewJobTerminal.endRun(runs, fence, ended.status());
                    yield Optional.empty();
                }
                case PoolWait waiting -> {
                    runs.requeue(fence, AgentRuns.Charge.FREE, LOCKED_RETRY, null);
                    yield Optional.empty();
                }
                case ClaimOutcome held -> {
                    runs.requeue(fence, AgentRuns.Charge.FREE, BUDGET_HOLD_INTERVAL, AgentRunHold.BUDGET);
                    yield Optional.empty();
                }
            };
        } finally {
            logScope.close();
        }
    }

    /** Fails a job whose inputs could not be prepared, and ends its run, in the caller's transaction. */
    private TerminalClaim failPreparation(ReviewJob job, AgentRunFence fence, JobPreparationException failure) {
        PreparationFailure preparation = PreparationFailure.of(PreparationFailure.Stage.INPUTS, failure);
        job.setStatus(ReviewJobStatus.FAILED);
        job.setCompletedAt(Instant.now());
        job.setErrorMessage(preparation.message());
        jobRepository.save(job);
        ReviewJobTerminal.endRun(runs, fence, ReviewJobStatus.FAILED);
        jobRepository.recordPreparationFailure(
                job.getId(),
                job.getWorkspaceId(),
                job.getAttempt(),
                job.getRunId(),
                preparation.diagnosis(objectMapper, job.getAttempt()));
        jobTelemetry.preparationFailed(job, preparation, "FAILED");
        return new TerminalClaim(job, ReviewJobStatus.FAILED);
    }

    private ClaimAttempt claimLocked(ReviewJob job, AgentRunFence fence) {
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
        // Past the retry limit, a composition and a review with staged results withhold what is open, without a model.
        if (job.spentAttempts() > maxRetries && (composing || retained))
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
            long poolId = callPermits.poolFor(snapshot.model().connectionRef());
            if (callPermits.hasReviewCallsWaiting(poolId)) return new PoolWait(fence.runId(), poolId);
        }

        if (!(ResultAdmissionService.isContinuation(job)
                ? reviewStart.compose(job.getWorkspaceId(), job.getReviewId())
                : reviewStart.start(job.getWorkspaceId(), job.getReviewId()))) return cancelWithParent(job);
        Instant claimedAt = Instant.now();
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
                snapshot = submitted.withPriceSnapshot(
                        submitted.model().priceSnapshot() == null
                                ? admitted.price()
                                : submitted.model().priceSnapshot());
            } else {
                methods.require(assigned.getFirst().practice().method(), "review", binding);
                snapshot = submitted;
            }
        } catch (LlmModelUnavailableException | ReviewJobStateConflictException failure) {
            return null;
        }
        return snapshot;
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
