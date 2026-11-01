package de.tum.cit.aet.hephaestus.productfeedback.drafting;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Drafting work for the worker's model adapter. The adapter sends the two messages to the named instance model and
 * reports the answer; product feedback validates it, redacts it and decides the route. Every result names the attempt
 * it was claimed under and is dropped when that attempt is no longer current.
 */
public interface ProductFeedbackDrafting {

    /**
     * One report to draft.
     *
     * @param attempt the report's drafting generation; the model-call fence, not a review attempt
     * @param llmModelId the instance model the operator chose
     * @param system the instructions; {@code user} carries the report as delimited data
     * @param deadline the lease end; the call must be over before it
     */
    record DraftWork(
            UUID feedbackId,
            int attempt,
            long llmModelId,
            String system,
            String user,
            int maxOutputTokens,
            Instant deadline) {}

    /** Why the adapter could not draft. */
    enum Failure {
        /** The model, its connection or its call pool is not usable. */
        MODEL_UNAVAILABLE,
        /** The call did not finish before the deadline. */
        TIMEOUT,
        /** The provider failed or refused the call. */
        PROVIDER_ERROR
    }

    /** Claims the next report waiting for a draft, or empty when none waits. */
    Optional<DraftWork> claim(String workerId);

    /** Whether the claim is still current; the adapter passes this as the call's {@code ended} check. */
    boolean held(UUID feedbackId, int attempt);

    /** Hands over the model's answer and the tokens of every physical call. */
    void complete(UUID feedbackId, int attempt, String answer, long inputTokens, long outputTokens);

    /** Reports that no draft came; the report routes without one. */
    void fail(UUID feedbackId, int attempt, Failure failure, long inputTokens, long outputTokens);
}
