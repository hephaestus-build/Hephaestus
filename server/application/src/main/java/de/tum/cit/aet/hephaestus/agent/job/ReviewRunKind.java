package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.run.AgentRun;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunHandler;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunHold;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunStopReason;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.core.TransactionCallbacks;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStart;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
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
@ConditionalOnServerRole
@WorkspaceAgnostic("Each call names a run, which carries its workspace")
public class ReviewRunKind implements AgentRunHandler {

    /** How many attempts past the retry limit may withhold what an admitted review still has open. */
    static final int WITHHOLD_ATTEMPTS = 3;

    private static final Duration COMPOSITION_CLOSE_WINDOW = Duration.ofMinutes(1);

    static final String LOST_AT_LIMIT = "The attempt's holder was lost, and the retry limit is reached.";

    static final String RETRIES_EXHAUSTED =
            "The review's attempts ended without a result more often than its retry limit allows.";

    private static final Logger log = LoggerFactory.getLogger(ReviewRunKind.class);

    private final ReviewJobRepository jobs;
    private final AgentRuns runs;
    private final AgentCapacityProperties capacity;
    private final PracticeReviewProperties properties;
    private final ReviewJobAssignments assignments;
    private final JobEvidenceFiles files;
    private final ObjectMapper mapper;
    private final ReviewStart reviewStart;
    private final MeterRegistry meters;

    ReviewRunKind(
            ReviewJobRepository jobs,
            AgentRuns runs,
            AgentCapacityProperties capacity,
            PracticeReviewProperties properties,
            ReviewJobAssignments assignments,
            JobEvidenceFiles files,
            ObjectMapper mapper,
            ReviewStart reviewStart,
            MeterRegistry meters) {
        this.jobs = jobs;
        this.runs = runs;
        this.capacity = capacity;
        this.properties = properties;
        this.assignments = assignments;
        this.files = files;
        this.mapper = mapper;
        this.reviewStart = reviewStart;
        this.meters = meters;
        compositionUnconfirmed(AgentRunStopReason.ATTEMPTS_EXHAUSTED);
        compositionUnconfirmed(AgentRunStopReason.DEADLINE);
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

    /** The copy fence, then the review, then its job, as every recovery, claim and admission takes them. */
    @Override
    public void lock(AgentRunRef run) {
        files.holdCopyAdmission();
        jobs.lockParentByRun(run.id(), run.workspaceId());
        jobs.lockByRun(run.id(), run.workspaceId());
    }

    /**
     * The job goes back to the queue, unless the lost lease spent its last retry: then the review fails now, and its
     * run ends with it. The reaper's write settles the lost claim's open calls.
     */
    @Override
    public LossOutcome lost(AgentRunFence lost) {
        ReviewJob job = jobs.lockByRun(lost.runId(), lost.workspaceId())
                .filter(found -> holds(found, lost))
                .orElse(null);
        if (job == null) return new Requeue();
        // The lost lease spends one retry, which the run counts once the reaper writes it.
        int spent = job.spentAttempts() + 1;
        if (!ResultAdmissionService.isAdmitted(job)
                && spent > capacity.reviewRetries() + (assignments.hasStaged(job) ? 1 : 0)) {
            jobs.transitionStatusOwnedBy(
                    job.getId(), ReviewJobStatus.FAILED, Instant.now(), LOST_AT_LIMIT, lost.runId(), job.getAttempt());
            log.info("Review attempt lost its lease at the retry limit, so the review fails: jobId={}", job.getId());
            return new End();
        }
        jobs.requeueRunning(job.getId(), lost.runId(), job.getAttempt());
        reviewStart.requeue(job.getWorkspaceId(), job.getReviewId());
        log.info("Review attempt lost its lease and is queued again: jobId={}", job.getId());
        return new Requeue();
    }

    @Override
    public void ended(AgentRunRef run, int lastEpoch, AgentRunStopReason reason) {
        ReviewJob job = jobs.lockByRun(run.id(), run.workspaceId()).orElse(null);
        if (job == null) return;
        Instant now = Instant.now();
        boolean queued = job.getStatus() == ReviewJobStatus.QUEUED;
        if (queued && !ResultAdmissionService.isAdmitted(job)) {
            jobs.transitionStatus(
                    job.getId(), ReviewJobStatus.FAILED, now, RETRIES_EXHAUSTED, Set.of(ReviewJobStatus.QUEUED));
            return;
        }
        if (!queued && (job.getStatus() != ReviewJobStatus.RUNNING || job.getAttempt() != attempt(lastEpoch))) return;

        if (ResultAdmissionService.isAdmitted(job)) {
            // Its results stay. Settlement closes the review as partial: nothing confirms each destination's decision.
            jobs.failCompositionUnconfirmed(
                    job.getId(),
                    run.id(),
                    job.getAttempt(),
                    now,
                    mapper.createObjectNode().put("outcome", RefusalOutcome.COMPOSITION_UNCONFIRMED.name()));
            // Counted once the reaper's take-back commits; a run that another replica took back rolls this back.
            var counter = compositionUnconfirmed(reason);
            TransactionCallbacks.afterCommit(counter::increment);
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
            UUID jobId, AgentRunFence fence, AgentRuns.Charge charge, Duration delay, @Nullable AgentRunHold hold) {
        files.holdCopyAdmission();
        jobs.lockParentOfJob(jobId);
        ReviewJob job = jobs.lockById(jobId).orElse(null);
        if (job == null || !holds(job, fence)) return false;
        if (jobs.requeueRunning(jobId, fence.runId(), attempt(fence.epoch())) == 0) return false;
        reviewStart.requeue(job.getWorkspaceId(), job.getReviewId());
        Duration wait = charge == AgentRuns.Charge.SPEND ? AgentRuns.backoff(job.spentAttempts() + 1) : delay;
        if (!runs.requeue(fence, charge, wait, hold))
            throw new IllegalStateException("The attempt's run moved under its lock");
        return true;
    }

    /**
     * Hands an admitted review from its measuring run on to the run that composes it, in the caller's transaction: the
     * measuring run ends, which settles its open model calls, and the job waits on a {@code REVIEW_COMPOSE} run that continues it
     * ({@link AgentRuns#continueAs}). The sandbox of the review drives the composition next.
     *
     * @return the composing run; empty when {@code fence} no longer holds the job
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AgentRunRef> continueToCompose(UUID jobId, AgentRunFence fence) {
        files.holdCopyAdmission();
        jobs.lockParentOfJob(jobId);
        ReviewJob job = jobs.lockById(jobId).orElse(null);
        if (job == null || !holds(job, fence)) return Optional.empty();
        if (runs.end(fence, AgentRunStopReason.COMPLETED) != AgentRuns.EndOutcome.ENDED) return Optional.empty();
        AgentRunRef ended = new AgentRunRef(fence.runId(), fence.workspaceId());
        AgentRunRef composing =
                runs.continueAs(ended, fence.epoch(), AgentRunKind.REVIEW_COMPOSE, compositionExecutionWindow());
        if (jobs.queueOnNextRun(jobId, fence.runId(), attempt(fence.epoch()), composing.id()) == 0)
            throw new IllegalStateException("The attempt's job moved under its lock");
        reviewStart.requeue(job.getWorkspaceId(), job.getReviewId());
        runs.retire(ended);
        return Optional.of(composing);
    }

    /** Hands a passing composition failure to a fresh run, spending one retry with its usual backoff. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean retryComposition(UUID jobId, AgentRunFence fence) {
        files.holdCopyAdmission();
        jobs.lockParentOfJob(jobId);
        var job = jobs.lockById(jobId).orElse(null);
        if (job == null || !holds(job, fence) || !ResultAdmissionService.isAdmitted(job)) return false;
        if (runs.end(fence, AgentRunStopReason.FAILED) != AgentRuns.EndOutcome.ENDED) return false;
        var ended = fence.run();
        var delay = AgentRuns.backoff(job.spentAttempts() + 1);
        var next = runs.continueAfter(ended, fence.epoch(), delay, compositionExecutionWindow());
        if (jobs.queueOnNextRun(jobId, fence.runId(), job.getAttempt(), next.id()) == 0)
            throw new IllegalStateException("The attempt's job moved under its lock");
        reviewStart.requeue(job.getWorkspaceId(), job.getReviewId());
        runs.retire(ended);
        return true;
    }

    /** Continues an expired composition, with its domain relink in the reaper's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LossOutcome continueExpiredComposition(AgentRunFence fence) {
        var job = jobs.lockByRun(fence.runId(), fence.workspaceId()).orElse(null);
        // A stale reaper may find no domain after the winner relinked it. Let the run CAS decide that race first.
        var delay = job == null ? Duration.ZERO : AgentRuns.backoff(job.spentAttempts() + 1);
        var next = runs.continueExpired(fence, delay, compositionExecutionWindow());
        if (job == null || !holds(job, fence))
            throw new IllegalStateException("The expired composition's domain does not hold its run");
        if (jobs.queueOnNextRun(job.getId(), fence.runId(), job.getAttempt(), next.id()) == 0)
            throw new IllegalStateException("The attempt's job moved under its lock");
        reviewStart.requeue(job.getWorkspaceId(), job.getReviewId());
        runs.retire(fence.run());
        return new Continued(next);
    }

    /**
     * Offers the workspace's jobs that its budget held at once, so a raised cap takes effect on the next claim rather
     * than up to an hour later. A job released under a lowered cap is held again at its next claim.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseBudgetHolds(long workspaceId) {
        runs.releaseHolds(workspaceId, AgentRunHold.BUDGET);
    }

    /**
     * Queues the composition of a completed review again when delivery proof can resume it: on a new run that
     * continues the ended one, so its attempts keep their own numbers.
     *
     * @return whether a composition was queued
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean resumeComposition(long workspaceId, UUID reviewId) {
        files.holdCopyAdmission();
        if (jobs.lockParentByReview(workspaceId, reviewId).isEmpty()) return false;
        var resumable = jobs.lockResumableComposition(workspaceId, reviewId, capacity.reviewRetries())
                .orElse(null);
        if (resumable == null) return false;
        AgentRunRef ended = new AgentRunRef(resumable.getRunId(), workspaceId);
        // A review that composed on its review run, before composition had a run of its own, resumes on one.
        AgentRunRef next = runs.find(ended).map(AgentRun::getKind).orElseThrow() == AgentRunKind.REVIEW_COMPOSE
                ? runs.continueAfter(ended, resumable.getEpoch(), Duration.ZERO, compositionExecutionWindow())
                : runs.continueAs(
                        ended, resumable.getEpoch(), AgentRunKind.REVIEW_COMPOSE, compositionExecutionWindow());
        jobs.queueOnRun(resumable.getId(), workspaceId, next.id());
        reviewStart.requeue(workspaceId, reviewId);
        runs.retire(ended);
        return true;
    }

    private Counter compositionUnconfirmed(AgentRunStopReason reason) {
        return Counter.builder(AgentMetrics.AGENT_JOB_COMPOSITION_STOPPED)
                .description("Admitted reviews whose runs ended before they confirmed each destination")
                .tag("cause", reason == AgentRunStopReason.DEADLINE ? "deadline" : "retries_exhausted")
                .tag("outcome", "unrecorded")
                .register(meters);
    }

    /** The run leaves time after composition to record its stop and close the attempt. */
    public static Instant compositionRunDeadline(Instant workDeadline) {
        return workDeadline.plus(COMPOSITION_CLOSE_WINDOW);
    }

    /** The work deadline shared by the model, composer and provider readback, before the driver's abort. */
    public static Instant compositionWorkDeadline(Instant runDeadline) {
        return runDeadline.minus(COMPOSITION_CLOSE_WINDOW);
    }

    private Duration compositionExecutionWindow() {
        return properties.compositionTimeout().plus(COMPOSITION_CLOSE_WINDOW);
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
}
