package de.tum.cit.aet.hephaestus.agent.job;

enum RefusalOutcome {
    WORKSPACE_REFUSED,
    /** No attempt writes it any more. Rows that an earlier release refused keep it, and settlement still reads it. */
    INSUFFICIENT_EVIDENCE,
    /** Every attempt of an admitted review ended before it confirmed a decision for each destination. */
    COMPOSITION_UNCONFIRMED,
    /** The model provider answered none of the review's calls, or limited their rate, until the review's bound passed. */
    PROVIDER_UNAVAILABLE
}
