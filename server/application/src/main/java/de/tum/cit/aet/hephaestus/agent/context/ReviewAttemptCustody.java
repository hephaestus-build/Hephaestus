package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService.AdmissionIdentity;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunState;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The custody of a review attempt's inputs while a sandbox runs it: the folder that the capture froze and the READY
 * receipt that vouches for the people it copied. A sandbox run is many gateway requests, so the inputs are held from
 * the first request that needs them until the run ends, not for one request. Both review run kinds hold their inputs
 * here: a measuring run the capture, a composing run the inputs it restores.
 *
 * <p>Each replica holds its local inputs. A new replica restores the attempt's published snapshot from PostgreSQL.
 */
@Component
public class ReviewAttemptCustody {

    private static final Logger log = LoggerFactory.getLogger(ReviewAttemptCustody.class);

    private final AgentRuns runs;
    private final Map<AdmissionIdentity, Held> held = new ConcurrentHashMap<>();

    public ReviewAttemptCustody(AgentRuns runs) {
        this.runs = runs;
    }

    /**
     * The attempt's inputs: prepared by the first call, and held until {@link #release}. A later call of the same
     * attempt waits for the first one and answers the same inputs; calls of other attempts never wait for it. A
     * {@code prepare} that throws holds nothing, so a later call prepares again, with its own {@code prepare}: a
     * composition whose inputs were erased then holds the inputs it stages without them. Call it outside a
     * transaction: preparing reads the providers.
     */
    public PreparedJobInputs hold(AdmissionIdentity attempt, Supplier<PreparedJobInputs> prepare) {
        return held.computeIfAbsent(attempt, key -> new Held()).get(prepare);
    }

    /**
     * Releases the attempt's inputs once the caller's transaction commits, or now outside one: its folder is retired,
     * and its capture closes. A transaction that rolls back keeps them, since its run did not end.
     */
    public void release(AdmissionIdentity attempt) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            close(attempt);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                close(attempt);
            }
        });
    }

    /**
     * Releases the inputs of every attempt whose run no longer holds them, such as one cancelled with its review: its
     * run ended, or a later claim holds it.
     */
    public void releaseEnded() {
        for (AdmissionIdentity attempt : held.keySet()) {
            boolean live = runs.find(new AgentRunRef(attempt.runId(), attempt.workspaceId()))
                    .filter(run -> run.getState() == AgentRunState.LEASED)
                    .filter(run -> run.getLeaseEpoch() == ReviewRunKind.epoch(attempt.attempt()))
                    .isPresent();
            if (!live) close(attempt);
        }
    }

    /** Releases now the inputs of every attempt of a job that this replica holds, as an erasure of its people does. */
    public void releaseJob(UUID jobId) {
        for (AdmissionIdentity attempt : held.keySet()) if (attempt.jobId().equals(jobId)) close(attempt);
    }

    private void close(AdmissionIdentity attempt) {
        Held inputs = held.remove(attempt);
        if (inputs == null) return;
        try {
            inputs.close();
        } catch (RuntimeException failure) {
            // The sweep of ended attempt folders removes what this close left.
            log.atWarn()
                    .addKeyValue("event.name", "agent.review.custody_release_failed")
                    .addKeyValue("error.type", failure.getClass().getName())
                    .setCause(failure)
                    .log("Could not release a review attempt's inputs");
        }
    }

    /** One attempt's inputs, prepared once. */
    private static final class Held {
        private @Nullable PreparedJobInputs inputs;
        private boolean closed;

        private final ReentrantLock preparation = new ReentrantLock();

        /**
         * Guards {@link #inputs} and {@link #closed}. A lock, not a monitor: closing inputs removes their folder and
         * records it, and on Java 21 a virtual thread that blocks under a monitor pins its carrier.
         */
        private final ReentrantLock state = new ReentrantLock();

        PreparedJobInputs get(Supplier<PreparedJobInputs> prepare) {
            preparation.lock();
            try {
                state.lock();
                try {
                    if (closed) throw new IllegalStateException("The attempt's inputs were released");
                    if (inputs != null) return inputs;
                } finally {
                    state.unlock();
                }
                // Capture admission can wait for erasure. Release must never wait for that admission.
                PreparedJobInputs prepared = prepare.get();
                state.lock();
                try {
                    if (closed) {
                        prepared.close();
                        throw new IllegalStateException("The attempt's inputs were released");
                    }
                    inputs = prepared;
                    return prepared;
                } finally {
                    state.unlock();
                }
            } finally {
                preparation.unlock();
            }
        }

        void close() {
            state.lock();
            try {
                closed = true;
                if (inputs != null) inputs.close();
            } finally {
                state.unlock();
            }
        }
    }
}
