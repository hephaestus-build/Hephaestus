package de.tum.cit.aet.hephaestus.agent.job;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Why a run that reached {@link AgentJobStatus#COMPLETED} produced the observations it did.
 *
 * <p>A run that skips automated review for insufficient evidence, or because an earlier review already answered
 * everything, completes successfully, because nothing failed — so status alone cannot distinguish it from a run that
 * assessed the work and found nothing. Without this enum such a run reads as a clean result.
 */
public enum ReviewRunOutcome {
    /** Automated review ran against sufficient evidence; no observations means none were identified. */
    REVIEWED,
    /**
     * Required evidence was missing, unreadable, out of date, or unauthorized, so no model ran and no
     * practice was assessed.
     */
    INSUFFICIENT_EVIDENCE,
    /**
     * The evidence was sufficient, but a completed review had already answered every ready practice on exactly the
     * same code, so no model ran. The answers are that review's observations, named in {@code answeredPractices};
     * this run recorded none of its own and assessed nothing anew.
     */
    COALESCED;

    static final String OUTPUT_FIELD = "outcome";

    /** Reads the outcome an executor recorded on {@code agent_job.output}; defaults to {@link #REVIEWED}. */
    static ReviewRunOutcome fromJobOutput(@Nullable JsonNode output) {
        if (output == null || !output.has(OUTPUT_FIELD)) {
            return REVIEWED;
        }
        String value = output.get(OUTPUT_FIELD).asString(null);
        if (INSUFFICIENT_EVIDENCE.name().equals(value)) return INSUFFICIENT_EVIDENCE;
        return COALESCED.name().equals(value) ? COALESCED : REVIEWED;
    }
}
