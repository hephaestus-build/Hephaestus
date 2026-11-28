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
     * A model call of a report draft run. Its source is the report and its source attempt is the report's generation.
     * The call belongs to the instance and its tokens count on the report.
     */
    PRODUCT_FEEDBACK_DRAFT,
    /** Synthetic capability checks; source_id groups the baseline and forced-choice calls, without a run. */
    PROBE,
    /**
     * A model call of a personal conversation with no workspace: the source is the personal attempt, and the personal
     * purse of the instance pays. No producer writes these rows yet.
     */
    PERSONAL_CONVERSATION;
}
