package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.proxy.ProxyAttempt;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * The server side of one run kind that a sandbox drives: what its loop reads as its context, and how its run ends with
 * its domain row. One bean for each kind; a kind without one is no route.
 */
public interface SandboxRunKind {

    AgentRunKind kind();

    /** The kind of sandbox that drives runs of this kind: its pull offers them, and no other sandbox's does. */
    AgentSandboxKind sandbox();

    /**
     * The run was leased to its sandbox, in the transaction of the sandbox's pull: the kind moves its domain row with
     * the run, as a review job goes to {@code RUNNING}, or a turn starts its attempt. False when the kind ended or
     * requeued the run instead, in the same transaction, as a claim that finds its work erased does; the sandbox then
     * gets no run from this pull.
     */
    boolean claimed(SandboxRun run);

    /**
     * The attempt that the model gateway routes and bills the run's model calls to, while the claim holds the run:
     * the domain row that the run drives, and the deadline of its work. Empty for a run whose kind makes no model
     * calls, which the gateway then refuses.
     */
    Optional<ProxyAttempt> attempt(SandboxRun run);

    /**
     * The run's context, as the kind's loop reads it from {@code GET …/runs/{runId}/context}. Called only while the
     * claim holds the run, outside any transaction: a kind that reads lazily opens its own read transaction. It reads
     * and writes nothing of the lease.
     */
    JsonNode context(SandboxRun run);

    /**
     * Ends the run and its domain row in the caller's transaction, or puts both back in the queue. True when the
     * claim still held the run, and also when this claim already ended it, as a turn's own end route does; false
     * answers the sandbox that its lease is gone.
     */
    boolean end(SandboxRun run, Outcome outcome);

    /** The registered kinds by the run kind that each drives; two beans of one run kind are a defect. */
    static Map<AgentRunKind, SandboxRunKind> byKind(List<SandboxRunKind> kinds) {
        var index = new EnumMap<AgentRunKind, SandboxRunKind>(AgentRunKind.class);
        for (SandboxRunKind kind : kinds) {
            if (index.put(kind.kind(), kind) != null)
                throw new IllegalStateException("Two sandbox runs of kind " + kind.kind());
        }
        return index;
    }

    /** How a sandbox ends a run that it holds. */
    enum Outcome {
        /** The run did its work. */
        COMPLETED,
        /** The run stopped before its work was done, and another claim goes on with it: it spends an attempt. */
        HANDED_ON;

        /** The outcome as the loop sends it: {@code completed} or {@code handed_on}. */
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
