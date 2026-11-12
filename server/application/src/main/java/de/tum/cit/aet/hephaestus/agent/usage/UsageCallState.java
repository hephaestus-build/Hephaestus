package de.tum.cit.aet.hephaestus.agent.usage;

/** Whether a model call's ledger row still waits for its end. */
public enum UsageCallState {
    /** Written before the call left; its claim still runs, or the claim's end has not settled it yet. */
    OPEN,
    /** Its claim ended first; a late measurement may still replace the settlement. */
    SETTLED,
    /** Measured by the call's end, including an end without reported usage. */
    ENDED,
}
