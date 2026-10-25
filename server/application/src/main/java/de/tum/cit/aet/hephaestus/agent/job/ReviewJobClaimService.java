package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelUnavailableException;
import de.tum.cit.aet.hephaestus.agent.composer.CompositionStop;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetBlockReason;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.integration.core.metrics.ReviewRefusalRecorder;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnWorkerRole
@ConditionalOnBooleanProperty(RuntimeRole.AGENT_ENABLED_PROPERTY)
@WorkspaceAgnostic("Claim admission locks one queued job by its global identifier")
public class ReviewJobClaimService {
    private static final Logger log = LoggerFactory.getLogger(ReviewJobClaimService.class);
    private static final Duration BUDGET_HOLD_INTERVAL = Duration.ofHours(1);
    private static final Duration BUDGET_HOLD_MAX_JOB_AGE = Duration.ofDays(7);
    private final ReviewJobRepository jobRepository;
    private final ReviewMemberAiPolicy memberAiPolicy;
    private final ReviewMethodVerifier methods;
    private final ReviewJobAssignments assignments;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final LlmBudgetService llmBudgetService;
    private final @Nullable LlmAdmissionService llmAdmissionService;
    private final ReviewJobTelemetry jobTelemetry;
    private final @Nullable String workerId;
    private final Counter concurrencyRejected;
    private final Timer claimLatency;

    public ReviewJobClaimService(
            ReviewJobRepository jobRepository,
            ReviewMemberAiPolicy memberAiPolicy,
            ReviewMethodVerifier methods,
            ReviewJobAssignments assignments,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            LlmBudgetService llmBudgetService,
            @Nullable LlmAdmissionService llmAdmissionService,
            ReviewJobTelemetry jobTelemetry,
            Optional<WorkerProperties> workerProperties) {
        this.jobRepository = jobRepository;
        this.memberAiPolicy = memberAiPolicy;
        this.methods = methods;
        this.assignments = assignments;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.llmBudgetService = llmBudgetService;
        this.llmAdmissionService = llmAdmissionService;
        this.jobTelemetry = jobTelemetry;
        this.workerId = workerProperties.map(WorkerProperties::resolvedWorkerId).orElse(null);
        this.concurrencyRejected = Counter.builder(AgentMetrics.AGENT_JOB_CONCURRENCY_REJECTED)
                .description("Jobs rejected due to concurrency limits")
                .register(meterRegistry);
        this.claimLatency = Timer.builder(AgentMetrics.AGENT_JOB_CLAIM_LATENCY)
                .description("Time between job submission and its claim")
                .register(meterRegistry);
    }

    sealed interface ClaimAttempt {}

    enum ClaimOutcome implements ClaimAttempt {
        ALREADY_CLAIMED,
        CONCURRENCY_FULL,
        BUDGET_HELD,
    }

    /**
     * A claimed attempt.
     *
     * @param withholdOnly for an admitted review handed on to compose, the cause a claim check met: the attempt makes no
     *     model call and withholds what is still open with it
     */
    record ClaimResult(
            ReviewJob job,
            ConfigSnapshot snapshot,
            String itemKind,
            @Nullable CompositionStop withholdOnly) implements ClaimAttempt {
        ClaimResult(ReviewJob job, ConfigSnapshot snapshot, String itemKind) {
            this(job, snapshot, itemKind, null);
        }
    }

    record TerminalClaim(ReviewJob job, ReviewJobStatus status) implements ClaimAttempt {}

    @Nullable
    ClaimAttempt claimJob(UUID jobId) {
        return transactionTemplate.execute(status -> {
            Optional<ReviewJob> locked = jobRepository.findByIdQueuedForUpdateSkipLocked(jobId, Instant.now());
            if (locked.isEmpty()) {
                log.debug("Job already claimed or not QUEUED: jobId={}", jobId);
                return ClaimOutcome.ALREADY_CLAIMED;
            }

            ReviewJob job = locked.get();
            try {
                return claimLocked(job);
            } catch (JobPreparationException failure) {
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
                jobRepository.recordPreparationFailure(
                        job.getId(),
                        job.getWorkspaceId(),
                        job.getAttempt(),
                        job.getWorkerId(),
                        objectMapper.writeValueAsString(diagnosis));
                jobTelemetry.preparationFailed(job, preparation, "FAILED");
                return new TerminalClaim(job, ReviewJobStatus.FAILED);
            }
        });
    }

    private ClaimAttempt claimLocked(ReviewJob job) {

        if (memberAiPolicy.isProcessingSuppressed(job)) {
            job.setStatus(ReviewJobStatus.CANCELLED);
            job.setCompletedAt(Instant.now());
            job.setErrorMessage("A personal-data erasure request blocks this job.");
            job.setCancellationReason(ReviewJobCancellationReason.PERSON_DATA_ERASED);
            jobRepository.save(job);
            recordPracticeReviewRefusal("person_data_erased");
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
            if (composing) return claimToWithhold(job, CompositionStop.NO_AI);
            job.setStatus(ReviewJobStatus.CANCELLED);
            job.setCompletedAt(Instant.now());
            job.setErrorMessage("The developer has not enabled AI practice reviews in this workspace.");
            job.setCancellationReason(ReviewJobCancellationReason.MEMBER_AI_DECLINED);
            jobRepository.save(job);
            recordPracticeReviewRefusal("member_ai_declined");
            return new TerminalClaim(job, ReviewJobStatus.CANCELLED);
        }

        LlmBudgetBlockReason blockReason =
                llmBudgetService.decide(job.getWorkspaceId()).forFunding(claimedFundingSource(job));
        if (blockReason != LlmBudgetBlockReason.NONE) {
            if (composing) return claimToWithhold(job, CompositionStop.BUDGET);
            return holdOrCancelOverBudget(job, blockReason);
        }

        // Live-revalidate the catalog binding immediately before RUNNING. Submit-time behaviour
        // stays frozen; only availability/grants and the price are refreshed, and a changed binding
        // is refused rather than silently switching the queued job to another model.
        WorkspaceAgentBinding binding = memberAiPolicy.binding(job).orElse(null);
        if (binding == null) {
            return composing ? claimToWithhold(job, CompositionStop.MODEL_UNAVAILABLE) : refuseUnavailableModel(job);
        }
        var assigned = assignments.load(job);
        ConfigSnapshot snapshot = admitModels(job, binding, assigned);
        if (snapshot == null)
            return composing ? claimToWithhold(job, CompositionStop.MODEL_UNAVAILABLE) : refuseUnavailableModel(job);
        String itemKind = assigned.getFirst().item().kind().value();
        // Admission holds this slot's binding row lock until the RUNNING transition commits.
        long runningCount = jobRepository.countRunningByWorkspaceIdAndDataHandlingTier(
                job.getWorkspaceId(), binding.getDataHandlingTier());
        if (runningCount >= binding.getMaxConcurrentJobs()) {
            concurrencyRejected.increment();
            log.info(
                    "Concurrency limit reached: jobId={}, workspaceId={}, purpose={}, running={}, max={}",
                    job.getId(),
                    job.getWorkspaceId(),
                    AgentPurpose.PRACTICE_REVIEW,
                    runningCount,
                    binding.getMaxConcurrentJobs());
            return ClaimOutcome.CONCURRENCY_FULL;
        }

        Instant claimedAt = Instant.now();
        if (!job.getAvailableAt().isAfter(claimedAt)) {
            claimLatency.record(Duration.between(job.getSubmittedAt(), claimedAt));
        }
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setStartedAt(claimedAt);
        job.setExecutionStartedAt(null);
        // Clear the budget hold marker so a crash requeue cannot inherit an expired hold.
        job.setHoldReason(null);
        job.setWorkerId(workerId);
        job.setConfigSnapshot(snapshot.toJson(objectMapper));
        jobRepository.save(job);

        return new ClaimResult(job, snapshot, itemKind);
    }

    /** Claims an attempt that makes no model call: it withholds what its review still has open, with this cause. */
    private ClaimResult claimToWithhold(ReviewJob job, CompositionStop cause) {
        String itemKind = assignments.load(job).getFirst().item().kind().value();
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setStartedAt(Instant.now());
        job.setExecutionStartedAt(null);
        job.setHoldReason(null);
        job.setWorkerId(workerId);
        jobRepository.save(job);
        return new ClaimResult(job, ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper), itemKind, cause);
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
            recordPracticeReviewRefusal("budget_exhausted");
            meterRegistry.counter(AgentMetrics.AGENT_JOB_BUDGET_REFUSED).increment();
            return new TerminalClaim(job, ReviewJobStatus.CANCELLED);
        }
        job.setAvailableAt(now.plus(BUDGET_HOLD_INTERVAL));
        // Marks this as a budget hold specifically, so raising the cap can release it at once instead
        // of leaving the admin to wait out BUDGET_HOLD_INTERVAL.
        job.setHoldReason(ReviewJob.HOLD_REASON_BUDGET);
        jobRepository.save(job);
        log.info(
                "Holding claim — monthly LLM budget {}: jobId={}, workspaceId={}, retryAt={}",
                blockReason == LlmBudgetBlockReason.EXHAUSTED ? "exhausted" : "unverifiable (cap set)",
                job.getId(),
                job.getWorkspaceId(),
                job.getAvailableAt());
        meterRegistry.counter(AgentMetrics.AGENT_JOB_BUDGET_HELD).increment();
        return ClaimOutcome.BUDGET_HELD;
    }

    private ClaimAttempt refuseUnavailableModel(ReviewJob job) {
        String message = "The configured model is not available.";
        job.setStatus(ReviewJobStatus.CANCELLED);
        job.setCompletedAt(Instant.now());
        job.setErrorMessage(message);
        job.setCancellationReason(ReviewJobCancellationReason.MODEL_UNAVAILABLE);
        jobRepository.save(job);
        meterRegistry.counter(AgentMetrics.AGENT_JOB_MODEL_REFUSED).increment();
        recordPracticeReviewRefusal("model_unavailable");
        log.info("Refusing claim — configured model unavailable: jobId={}", job.getId());
        return new TerminalClaim(job, ReviewJobStatus.CANCELLED);
    }

    private void recordPracticeReviewRefusal(String reason) {
        ReviewRefusalRecorder.record(meterRegistry, "execution", reason);
    }

    private @Nullable UsageFundingSource claimedFundingSource(ReviewJob job) {
        return memberAiPolicy
                .binding(job)
                .map(WorkspaceAgentBinding::getFundingSource)
                .orElse(null);
    }
}
