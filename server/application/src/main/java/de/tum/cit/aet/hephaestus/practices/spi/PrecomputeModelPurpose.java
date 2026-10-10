package de.tum.cit.aet.hephaestus.practices.spi;

/**
 * The agent purpose whose binding serves one model slot of a precompute script. Each constant has the
 * name of an {@code AgentPurpose} constant, so the wire value is that purpose and a client needs no map
 * from slot to purpose. The practices module has no dependency on the agent module, so it cannot name
 * {@code AgentPurpose} itself.
 */
public enum PrecomputeModelPurpose {
    /** The chat slot: the review's own model. */
    PRACTICE_REVIEW,
    PRACTICE_DECISION,
    PRACTICE_EMBEDDING,
    PRACTICE_RERANKING
}
