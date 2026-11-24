package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import java.util.Locale;

/** What a sandbox serves. A kind uses a chart template and its warm pool. */
public enum AgentSandboxKind {
    /** A practice review: it measures, then composes. */
    REVIEW,
    /** A conversation: its turns and digests, while the conversation is in use. */
    CONVERSATION,
    /** An instance product feedback report draft. */
    DRAFT_REPORT;

    /** The kind as the sandbox's template and its loop name it. */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
