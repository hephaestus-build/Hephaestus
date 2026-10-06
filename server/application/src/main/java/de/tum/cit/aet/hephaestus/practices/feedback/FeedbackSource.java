package de.tum.cit.aet.hephaestus.practices.feedback;

/**
 * The content-provenance axis of a {@link Feedback} unit — who authored its body, so policy/fallback units are
 * never scored as model output.
 *
 * <p>Every unit's CONTENT is authored by the model in the review run: admission only publishes a complete text
 * whole or refuses it (see {@code ComposedReviewAdmission}). It never synthesises a substitute body, and there is
 * no fallback content generator. Add a value only when an actual non-model author of feedback CONTENT exists.
 *
 * <p>Constrained at the DB by {@code chk_feedback_source} (currently {@code AGENT} only).
 */
public enum FeedbackSource {
    /** Synthesised by the LLM delivery agent. */
    AGENT,
}
