package de.tum.cit.aet.hephaestus.agent.usage;

import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import java.util.EnumSet;
import java.util.Set;

public enum UsageSourceType {
    REVIEW_JOB,
    MENTOR_TURN,
    PRACTICE_SCRIPT_DECISION,
    /**
     * The embedding model calls of one review attempt's practice scripts; the source is the job.
     */
    PRACTICE_SCRIPT_EMBEDDING,
    /**
     * The reranking model calls of one review attempt's practice scripts; the source is the job.
     */
    PRACTICE_SCRIPT_RERANKING;

    /**
     * The ledger rows of a review attempt's practice scripts: every model kind they call, never the
     * review's own.
     */
    public static final Set<UsageSourceType> PRACTICE_SCRIPT =
            EnumSet.of(PRACTICE_SCRIPT_DECISION, PRACTICE_SCRIPT_EMBEDDING, PRACTICE_SCRIPT_RERANKING);

    /** The source of a review call on a model of this kind: a chat call bills as the review's own. */
    public static UsageSourceType ofReviewCall(ModelKind kind) {
        return switch (kind) {
            case CHAT -> REVIEW_JOB;
            case DECISION -> PRACTICE_SCRIPT_DECISION;
            case EMBEDDING -> PRACTICE_SCRIPT_EMBEDDING;
            case RERANKING -> PRACTICE_SCRIPT_RERANKING;
        };
    }
}
