package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

/**
 * The terminal writes of a review attempt, in the caller's transaction: whoever ends the attempt, the worker or the
 * sandbox's own end, ends it the same way. Each write is fenced on the attempt's run, so a late write cannot end an
 * attempt that was requeued since, and the run ends with its job.
 */
@Component
@ConditionalOnAgentRuntime
@ConditionalOnBooleanProperty(RuntimeRole.AGENT_ENABLED_PROPERTY)
public class ReviewJobTerminal {

    private static final Logger log = LoggerFactory.getLogger(ReviewJobTerminal.class);

    /**
     * The key of a job's output under which a measuring attempt that handed its admitted review on records how its
     * measurement ended, when it did not complete: {@code {"status": "TIMED_OUT", "error": "..."}}.
     */
    public static final String MEASURED = "measured";

    private final ReviewJobRepository jobs;
    private final AgentRuns runs;
    private final @Nullable ReviewCompositionContinuation continuation;

    /**
     * @param continuation absent where this process captures no work, so a composition keeps no inputs
     */
    @Autowired
    public ReviewJobTerminal(
            ReviewJobRepository jobs, AgentRuns runs, ObjectProvider<ReviewCompositionContinuation> continuation) {
        this(jobs, runs, continuation.getIfAvailable());
    }

    ReviewJobTerminal(ReviewJobRepository jobs, AgentRuns runs, @Nullable ReviewCompositionContinuation continuation) {
        this.jobs = jobs;
        this.runs = runs;
        this.continuation = continuation;
    }

    /** Moves the attempt's job to a terminal status and ends its run; 0 when the attempt no longer owns the job. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int transition(UUID jobId, AgentRunFence fence, ReviewJobStatus status, Instant at, @Nullable String error) {
        return end(jobId, fence, status, at, error);
    }

    /** Ends the attempt refused, with its run; {@code output} says why. 0 when the attempt no longer owns the job. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int refuse(UUID jobId, AgentRunFence fence, JsonNode output) {
        int updated = jobs.transitionToEvidenceRefused(
                jobId, fence.runId(), ReviewRunKind.attempt(fence.epoch()), Instant.now(), output);
        if (updated > 0) runs.end(fence);
        return updated;
    }

    private int end(UUID jobId, AgentRunFence fence, ReviewJobStatus status, Instant at, @Nullable String error) {
        int updated = jobs.transitionStatusOwnedBy(
                jobId, status, at, error, fence.runId(), ReviewRunKind.attempt(fence.epoch()));
        if (updated > 0) runs.end(fence);
        return updated;
    }

    /**
     * Ends an attempt that composed its review’s feedback. Its run’s end settles open model calls. The job keeps
     * what its measuring attempt recorded under {@link #MEASURED}; only a completed measurement waits for delivery.
     * After the commit, the inputs that the composition kept go.
     *
     * @return false when the attempt no longer owns the job
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean completeComposed(UUID jobId, AgentRunFence fence) {
        JsonNode measured = jobs.findById(jobId)
                .map(ReviewJob::getOutput)
                .map(output -> output.path(MEASURED))
                .orElse(MissingNode.getInstance());
        ReviewJobStatus status = measured.isObject()
                ? ReviewJobStatus.valueOf(measured.path("status").asString())
                : ReviewJobStatus.COMPLETED;
        String error =
                measured.path("error").isString() ? measured.path("error").asString() : null;
        if (end(jobId, fence, status, Instant.now(), error) == 0) return false;
        ReviewJob completed = jobs.findById(jobId).orElseThrow();
        if (status == ReviewJobStatus.COMPLETED) completed.setProcessingStatus(ProcessingStatus.PENDING);
        jobs.saveAndFlush(completed);
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    forgetCompositionInputs(completed);
                }
            });
        else forgetCompositionInputs(completed);
        return true;
    }

    /** Removes the inputs that an admitted review kept for its composition; a failure leaves them to their sweep. */
    public void forgetCompositionInputs(ReviewJob job) {
        ReviewCompositionContinuation kept = continuation;
        if (kept == null) return;
        try {
            kept.forget(job);
        } catch (RuntimeException e) {
            log.warn("Could not remove original composition inputs: jobId={}", job.getId(), e);
        }
    }
}
