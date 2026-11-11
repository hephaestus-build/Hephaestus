package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import java.util.Locale;

/** What a sandbox serves. Each kind has a {@code SandboxTemplate} and a warm pool of its own in the chart. */
public enum AgentSandboxKind {
    /** A practice review: it measures, then composes. */
    REVIEW,
    /** A conversation: its turns and digests, while the conversation is in use. */
    CONVERSATION;

    /** The kind as the sandbox's template and its loop name it. */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
