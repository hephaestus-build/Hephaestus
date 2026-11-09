package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import java.util.Locale;
import java.util.Set;

/** What a sandbox serves. Each kind has a {@code SandboxTemplate} and a warm pool of its own in the chart. */
public enum AgentSandboxKind {
    /** A practice review: it measures, then composes. */
    REVIEW,
    /** A conversation: its turns and digests, while the conversation is in use. */
    CONVERSATION,
    /** One action task, such as a product feedback draft. */
    ACTION;

    /**
     * The run kinds that a sandbox of this kind leases through the gateway. A run kind joins here when its domain claim
     * goes through the sandbox's pull. Review jobs are still claimed by the worker's executor, so no kind leases one.
     */
    public Set<AgentRunKind> runs() {
        return Set.of();
    }

    /** The kind as the sandbox's template and its loop name it. */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
