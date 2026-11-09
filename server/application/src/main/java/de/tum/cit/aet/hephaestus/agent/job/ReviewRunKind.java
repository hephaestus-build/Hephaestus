package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunHandler;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunStopReason;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.core.TransactionCallbacks;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Review jobs on runs: one run per job, keyed to its review. A job moves with its run in one transaction: queued with
 * queued, running with leased, ended with ended, and the database refuses a commit where they disagree. Requeues and
 * lost leases keep every admitted and staged result and bill the attempt's measured spend before the job moves on.
 */
@Component
@ConditionalOnCaptureRole
@WorkspaceAgnostic("Each call names a run, which carries its workspace")
public class ReviewRunKind implements AgentRunHandler {

    /** How many attempts past the retry limit may withhold what an admitted review still has open. */
    static final int WITHHOLD_ATTEMPTS = 3;

    static final String LOST_AT_LIMIT = "The attempt's holder was lost, and the retry limit is reached.";

    static final String RETRIES_EXHAUSTED =
            "The review's attempts ended without a result more often than its retry limit allows.";

    private static final Logger log = LoggerFactory.getLogger(ReviewRunKind.class);

    private final ReviewJobRepository jobs;
    private final AgentRuns runs;
    private final AgentCapacityProperties capacity;
    private final ReviewAttemptUsageService usage;
    private final ReviewJobAssignments assignments;
    private final JobEvidenceFiles files;
    private final ObjectMapper mapper;
    private final Counter compositionUnconfirmed;

    ReviewRunKind(
            ReviewJobRepository jobs,
            AgentRuns runs,
            AgentCapacityProperties capacity,
            ReviewAttemptUsageService usage,
            ReviewJobAssignments assignments,
            JobEvidenceFiles files,
            ObjectMapper mapper,
            MeterRegistry meters) {
        this.jobs = jobs;
        this.runs = runs;
        this.capacity = capacity;
        this.usage = usage;
        this.assignments = assignments;
        this.files = files;
        this.mapper = mapper;
        this.compositionUnconfirmed = Counter.builder(AgentMetrics.AGENT_JOB_COMPOSITION_STOPPED)
                .description("Admitted reviews whose attempts all ended before they confirmed each destination")
                .tag("cause", "retries_exhausted")
                .tag("outcome", "unrecorded")
                .register(meters);
    }

    /** The band of a review's run: scheduled and backfilled reviews wait behind the ones that work or a person started. */
    public static AgentRunBand band(ReviewOrigin origin) {
        return switch (origin) {
            case SCHEDULE, BACKFILL -> AgentRunBand.BACKGROUND;
            case EVENT, REQUEST -> AgentRunBand.BATCH;
        };
    }

    /**
     * The claim epoch of a review attempt. A review numbers its attempts from 0, as its stored results and evidence
     * do; its run numbers its claims from 1. This and {@link #attempt} are the one conversion between the two.
     */
    public static int epoch(int attempt) {
        return attempt + 1;
    }

    /** The review attempt that the claim numbered {@code epoch} runs. */
    public static int attempt(int epoch) {
        return epoch - 1;
    }

    /**
     * The job's attempt number in SQL, over its run {@code r}: the attempt that runs or ran last, or, while the run
     * waits, the number its next claim takes.
     */
    public static final String ATTEMPT_OF_RUN =
            "(CASE WHEN r.state = 'QUEUED' THEN r.lease_epoch ELSE r.lease_epoch - 1 END)";

    /** The subject of a review's runs: one review runs one attempt at a time. */
    public static String subject(UUID reviewId) {
        return "review:" + reviewId;
    }

    @Override
    public AgentRunKind kind() {
        return AgentRunKind.REVIEW;
    }

    /**
     * Past the retry limit, a lost attempt still goes back to the queue a few times: its claim then only seals and
     * withholds what the review has open, or fails it with {@link #RETRIES_EXHAUSTED}.
     */
    @Override
    public int retries() {
        return capacity.reviewRetries() + WITHHOLD_ATTEMPTS;
    }

    /** The copy fence before any job row, as every recovery and admission takes them. */
    @Override
    public void lock(AgentRunRef run) {
        files.holdCopyAdmission();
        jobs.lockByRun(run.id(), run.workspaceId());
    }

    /**
     * The lost attempt is billed, and the job goes back to the queue with it, unless the lost lease spent its last
     * retry: then the review fails now, and its run ends with it.
     */
    @Override
    public boolean lost(AgentRunFence lost) {
        ReviewJob job = jobs.lockByRun(lost.runId(), lost.workspaceId())
                .filter(found -> holds(found, lost))
                .orElse(null);
        if (job == null) return true;
        settle(job);
        // The lost lease spends one retry, which the run counts once the reaper writes it.
        int spent = job.spentAttempts() + 1;
        if (!ResultAdmissionService.isAdmitted(job)
                && spent > capacity.reviewRetries() + (assignments.hasStaged(job) ? 1 : 0)) {
            jobs.transitionStatusOwnedBy(
                    job.getId(), ReviewJobStatus.FAILED, Instant.now(), LOST_AT_LIMIT, lost.runId(), job.getAttempt());
            log.info("Review attempt lost its lease at the retry limit, so the review fails: jobId={}", job.getId());
            return false;
        }
        jobs.requeueRunning(job.getId(), lost.runId(), job.getAttempt(), null);
        log.info("Review attempt lost its lease and is queued again: jobId={}", job.getId());
        return true;
    }

    @Override
    public void ended(AgentRunRef run, int lastEpoch, AgentRunStopReason reason) {
        ReviewJob job = jobs.lockByRun(run.id(), run.workspaceId()).orElse(null);
        if (job == null) return;
        Instant now = Instant.now();
        if (job.getStatus() == ReviewJobStatus.QUEUED) {
            jobs.transitionStatus(
                    job.getId(), ReviewJobStatus.FAILED, now, RETRIES_EXHAUSTED, Set.of(ReviewJobStatus.QUEUED));
            return;
        }
        if (job.getStatus() != ReviewJobStatus.RUNNING || job.getAttempt() != attempt(lastEpoch)) return;
        settle(job);
        if (ResultAdmissionService.isAdmitted(job)) {
            // Its results stay. Settlement closes the review as partial: nothing confirms each destination's decision.
            jobs.failCompositionUnconfirmed(
                    job.getId(),
                    run.id(),
                    attempt(lastEpoch),
                    now,
                    mapper.createObjectNode().put("outcome", RefusalOutcome.COMPOSITION_UNCONFIRMED.name()));
            // Counted once the reaper's take-back commits; a run that another replica took back rolls this back.
            TransactionCallbacks.afterCommit(compositionUnconfirmed::increment);
        } else {
            jobs.transitionStatusOwnedBy(
                    job.getId(), ReviewJobStatus.FAILED, now, RETRIES_EXHAUSTED, run.id(), attempt(lastEpoch));
        }
        log.info("Review run ended without its holder: jobId={}, reason={}", job.getId(), reason);
    }

    /**
     * Hands the running attempt back to the queue for its next one, keeping what it admitted and staged. A charged
     * requeue spends a retry and waits its backoff; a free one waits {@code delay}.
     *
     * @return whether the attempt still held its job
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean requeue(
            UUID jobId, AgentRunFence fence, AgentRuns.Charge charge, Duration delay, @Nullable String holdReason) {
        files.holdCopyAdmission();
        ReviewJob job = jobs.lockById(jobId).orElse(null);
        if (job == null || !holds(job, fence)) return false;
        settle(job);
        if (jobs.requeueRunning(jobId, fence.runId(), attempt(fence.epoch()), holdReason) == 0) return false;
        Duration wait = charge == AgentRuns.Charge.SPEND ? AgentRuns.backoff(job.spentAttempts() + 1) : delay;
        if (!runs.requeue(fence, charge, wait))
            throw new IllegalStateException("The attempt's run moved under its lock");
        return true;
    }

    /**
     * Offers the workspace's jobs that its budget held at once, so a raised cap takes effect on the next claim rather
     * than up to an hour later. A job released under a lowered cap is held again at its next claim.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseBudgetHolds(long workspaceId) {
        runs.releaseAll(workspaceId, jobs.clearBudgetHolds(workspaceId));
    }

    /**
     * Queues the composition of a completed review again when delivery proof can resume it: on a new run that
     * continues the ended one, so its attempts keep their own numbers.
     *
     * @return whether a composition was queued
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean resumeComposition(long workspaceId, UUID reviewId) {
        var resumable = jobs.lockResumableComposition(workspaceId, reviewId, capacity.reviewRetries())
                .orElse(null);
        if (resumable == null) return false;
        AgentRunRef ended = new AgentRunRef(resumable.getRunId(), workspaceId);
        AgentRunRef next = runs.continueAfter(ended, resumable.getEpoch());
        jobs.queueOnRun(resumable.getId(), workspaceId, next.id());
        runs.retire(ended);
        return true;
    }

    /** Whether the claim of {@code fence} still runs the job. */
    static boolean holds(ReviewJob job, AgentRunFence fence) {
        return job.getStatus() == ReviewJobStatus.RUNNING
                && job.getRunId().equals(fence.runId())
                && job.getAttempt() == attempt(fence.epoch());
    }

    /**
     * Whether a running job that is not admitted may spend one more retry: one more than the limit for a review with
     * staged results, whose next attempt only seals and withholds them.
     */
    boolean mayRetry(ReviewJob job) {
        if (ResultAdmissionService.isAdmitted(job)) return false;
        return job.spentAttempts() < capacity.reviewRetries() + (assignments.hasStaged(job) ? 1 : 0);
    }

    /**
     * Whether a queued job spent every attempt its retry limit gives it: one more for a review with staged results,
     * which seals and withholds them, and {@link #WITHHOLD_ATTEMPTS} more for an admitted review, which withholds what
     * it has open.
     */
    boolean retriesExhausted(ReviewJob job, boolean staged) {
        int limit = capacity.reviewRetries();
        return ResultAdmissionService.isAdmitted(job)
                ? job.spentAttempts() >= limit + WITHHOLD_ATTEMPTS
                : job.spentAttempts() > limit + (staged ? 1 : 0);
    }

    /**
     * Keeps the attempt's measurement and bills it before the job is handed on: the requeue zeroes the counters and the
     * next claim replaces the configuration, so only the locked attempt may settle it.
     */
    private void settle(ReviewJob job) {
        if (job.getExecutionStartedAt() == null) return;
        ReviewJobLlmUsage counts = jobs.findLlmUsageById(job.getId()).orElse(null);
        Map<ModelKind, FrozenCharge> charges = admittedCharges(job);
        usage.holdAttempt(job, charges, counts);
        usage.settleAttempt(job, charges, null, counts);
    }

    private Map<ModelKind, FrozenCharge> admittedCharges(ReviewJob job) {
        ConfigSnapshot snapshot = frozen(job);
        LlmPriceSnapshot price = snapshot != null && snapshot.model().priceSnapshot() != null
                ? snapshot.model().priceSnapshot()
                : LlmPriceSnapshot.unpricedInstance();
        return FrozenCharge.admitted(snapshot, price);
    }

    private @Nullable ConfigSnapshot frozen(ReviewJob job) {
        try {
            return ConfigSnapshot.fromJson(job.getConfigSnapshot(), mapper);
        } catch (RuntimeException failure) {
            log.warn("Cannot read frozen model for job {}", job.getId(), failure);
            return null;
        }
    }
}
