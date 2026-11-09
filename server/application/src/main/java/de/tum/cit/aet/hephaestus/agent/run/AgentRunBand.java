package de.tum.cit.aet.hephaestus.agent.run;

/** The claim priority of a run: every queued run of a band is offered before any run of the next band. */
public enum AgentRunBand {
    /** A person waits for it. */
    INTERACTIVE,
    /** Work that a change or a request started. */
    BATCH,
    /** Scheduled work that nobody waits for. */
    BACKGROUND
}
