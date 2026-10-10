package de.tum.cit.aet.hephaestus.agent.usage;

/** Kind of durable source that produced a usage-ledger attempt. */
public enum LlmUsageSourceType {
    AGENT_JOB,
    MENTOR_TURN,
    /** The decision model calls of one review attempt's precompute scripts; the source is the job. */
    PRECOMPUTE_DECISION,
    /** The embedding model calls of one review attempt's precompute scripts; the source is the job. */
    PRECOMPUTE_EMBEDDING,
    /** The reranking model calls of one review attempt's precompute scripts; the source is the job. */
    PRECOMPUTE_RERANKING,
}
