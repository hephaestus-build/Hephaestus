package de.tum.cit.aet.hephaestus.agent.usage;

public enum UsageSourceType {
    REVIEW_JOB,
    MENTOR_TURN,
    /**
     * The decision, embedding and reranking calls of one review attempt's practice scripts; the source is the job.
     * Nothing writes these rows any more. Erasure, repricing and the person-data export still read the past ones.
     */
    PRACTICE_SCRIPT_DECISION,
    PRACTICE_SCRIPT_EMBEDDING,
    PRACTICE_SCRIPT_RERANKING;
}
