package de.tum.cit.aet.hephaestus.agent.run;

/**
 * The priority of a run and of its model calls. The claim offers every queued run of a band before any run of the next
 * band, and a call pool grants every waiting call of a band before any call of the next band.
 */
public enum AgentRunBand {
    /** A person waits for it. */
    INTERACTIVE,
    /** Work that a change or a request started. */
    BATCH,
    /** Scheduled work that nobody waits for. */
    BACKGROUND
}
