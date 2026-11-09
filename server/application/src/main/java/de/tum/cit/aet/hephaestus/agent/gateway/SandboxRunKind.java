package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/**
 * The server side of one run kind that a sandbox drives: what its loop reads as its context, and how its run ends with
 * its domain row. One bean for each kind; a kind without one is no route.
 */
public interface SandboxRunKind {

    AgentRunKind kind();

    /**
     * The run's context, as the kind's loop reads it from {@code GET …/runs/{runId}/context}. Called only while the
     * claim holds the run; it reads and writes nothing of the lease.
     */
    JsonNode context(SandboxRun run);

    /**
     * Ends the run and its domain row in the caller's transaction, or puts both back in the queue. Whether the claim
     * still held the run: false answers the sandbox that its lease is gone.
     */
    boolean end(SandboxRun run, Outcome outcome);

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
