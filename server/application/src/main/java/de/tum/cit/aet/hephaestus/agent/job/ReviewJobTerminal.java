package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
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
import tools.jackson.databind.ObjectMapper;

/**
 * The terminal writes of a review attempt, in the caller's transaction: whoever ends the attempt, the worker or the
 * sandbox's own end, ends it the same way. Each write is fenced on the attempt's run, so a late write cannot end an
 * attempt that was requeued since, and the run ends with its job.
 */
@Component
@ConditionalOnWorkerRole
@ConditionalOnBooleanProperty(RuntimeRole.AGENT_ENABLED_PROPERTY)
public class ReviewJobTerminal {

    private static final Logger log = LoggerFactory.getLogger(ReviewJobTerminal.class);

    private final ReviewJobRepository jobs;
    private final AgentRuns runs;
    private final ReviewAttemptUsageService attemptUsage;
    private final ObjectMapper objectMapper;
    private final @Nullable LlmAdmissionService admission;
    private final @Nullable ReviewCompositionContinuation continuation;

    /**
     * @param admission absent where no model catalog admits models, so an attempt's price may be unpriced
     * @param continuation absent where this process captures no work, so a composition keeps no inputs
     */
    @Autowired
    public ReviewJobTerminal(
            ReviewJobRepository jobs,
            AgentRuns runs,
            ReviewAttemptUsageService attemptUsage,
            ObjectMapper objectMapper,
            ObjectProvider<LlmAdmissionService> admission,
            ObjectProvider<ReviewCompositionContinuation> continuation) {
        this(jobs, runs, attemptUsage, objectMapper, admission.getIfAvailable(), continuation.getIfAvailable());
    }

    ReviewJobTerminal(
            ReviewJobRepository jobs,
            AgentRuns runs,
            ReviewAttemptUsageService attemptUsage,
            ObjectMapper objectMapper,
            @Nullable LlmAdmissionService admission,
            @Nullable ReviewCompositionContinuation continuation) {
        this.jobs = jobs;
        this.runs = runs;
        this.attemptUsage = attemptUsage;
        this.objectMapper = objectMapper;
        this.admission = admission;
        this.continuation = continuation;
    }

    /** Moves the attempt's job to a terminal status and ends its run; 0 when the attempt no longer owns the job. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int transition(UUID jobId, AgentRunFence fence, ReviewJobStatus status, Instant at, @Nullable String error) {
        int updated = jobs.transitionStatusOwnedBy(
                jobId, status, at, error, fence.runId(), ReviewRunKind.attempt(fence.epoch()));
        if (updated > 0) runs.end(fence);
        return updated;
    }

    /**
     * Completes an attempt that composed its review's feedback: the job and its run end, its delivery waits, and the
     * attempt is settled at its admitted charge. After the commit, or at once outside a synchronized transaction, the
     * inputs that the composition kept go.
     *
     * @return false when the attempt no longer owns the job
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean completeComposed(UUID jobId, AgentRunFence fence) {
        if (transition(jobId, fence, ReviewJobStatus.COMPLETED, Instant.now(), null) == 0) return false;
        ReviewJob completed = jobs.findById(jobId).orElseThrow();
        ConfigSnapshot snapshot = ConfigSnapshot.fromJson(completed.getConfigSnapshot(), objectMapper);
        completed.setProcessingStatus(ProcessingStatus.PENDING);
        attemptUsage.settleAttempt(
                completed,
                FrozenCharge.admitted(snapshot, admittedPrice(snapshot)),
                null,
                jobs.findLlmUsageById(jobId).orElse(null));
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

    /** The price that the attempt's model was admitted at; a started attempt has one wherever a catalog admits. */
    public LlmPriceSnapshot admittedPrice(ConfigSnapshot snapshot) {
        if (snapshot.model().priceSnapshot() != null) return snapshot.model().priceSnapshot();
        if (admission != null) throw new IllegalStateException("Started job has no admitted LLM price snapshot");
        return LlmPriceSnapshot.unpricedInstance();
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
