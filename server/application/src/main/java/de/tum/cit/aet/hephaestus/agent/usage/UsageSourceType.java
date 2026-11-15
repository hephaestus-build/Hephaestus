package de.tum.cit.aet.hephaestus.agent.usage;

public enum UsageSourceType {
    REVIEW_JOB,
    CONVERSATION_TURN,
    /**
     * The decision, embedding and reranking calls of one review attempt's practice scripts; the source is the job.
     * Nothing writes these rows any more. Erasure, repricing and the person-data export still read the past ones.
     */
    PRACTICE_SCRIPT_DECISION,
    PRACTICE_SCRIPT_EMBEDDING,
    PRACTICE_SCRIPT_RERANKING,
    /**
     * The one model call that drafts a product-feedback report; the source is the report, and the number is the
     * generation in the call's {@code draft_report} action. It belongs to no workspace, and its tokens count on the
     * report.
     */
    PRODUCT_FEEDBACK_DRAFT,
    /** Synthetic capability checks; source_id groups the baseline and forced-choice calls, without a run. */
    PROBE;
}
