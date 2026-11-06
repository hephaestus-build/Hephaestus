package de.tum.cit.aet.hephaestus.agent.job;

enum RefusalOutcome {
    WORKSPACE_REFUSED,
    INSUFFICIENT_EVIDENCE,
    /** Every attempt of an admitted review ended before it confirmed a decision for each destination. */
    COMPOSITION_UNCONFIRMED
}
