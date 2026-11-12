package de.tum.cit.aet.hephaestus.productfeedback.drafting;

import java.util.Optional;
import java.util.UUID;

/** The report facts and quota that the model proxy reads, and the usage it records. */
public interface ProductFeedbackDraftCalls {
    /**
     * The one call that drafts a generation: two messages to the instance model the operator chose.
     *
     * @param system the instructions; {@code user} carries the report as delimited data
     */
    record DraftCall(long llmModelId, String system, String user, int maxOutputTokens) {}

    /** The call of this generation while the report still waits for it, with the instance model chosen now. */
    Optional<DraftCall> waiting(UUID feedbackId, int generation);

    /** Whether the report still waits for this generation's draft; a call that waits for capacity asks this often. */
    boolean waits(UUID feedbackId, int generation);

    /** Reserves daily quota in the ledger opening transaction, after the pool granted capacity. */
    boolean admitCall(UUID feedbackId, int generation, long modelId);

    /** Locks the report before its usage row, in the same order as erasure. */
    void lockForUsage(UUID feedbackId);

    /** Adds the tokens of one model call to the report. */
    void count(UUID feedbackId, long inputTokens, long outputTokens);
}
