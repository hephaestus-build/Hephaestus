package de.tum.cit.aet.hephaestus.agent.job;

import java.util.UUID;
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
    COALESCED,
    /**
     * The waiting attempt was replaced by an admitted newer push, edit or linked-work review of the same author's
     * current work, named in {@code coveringJobId}, which carries every practice this one selects. The replacement makes
     * no additional assessment, and any earlier attempt's history remains. Admission is not a result: the newer review
     * may still be waiting or running.
     */
    SUPERSEDED;

    static final String OUTPUT_FIELD = "outcome";

    /** The newer review a {@link #SUPERSEDED} run names. */
    static final String COVERING_JOB_FIELD = "coveringJobId";

    /** Reads the outcome recorded on {@code agent_job.output}; defaults to {@link #REVIEWED}. */
    static ReviewRunOutcome fromJobOutput(@Nullable JsonNode output) {
        return fromRecordedValue(
                output == null ? null : output.path(OUTPUT_FIELD).asString(null));
    }

    static ReviewRunOutcome fromRecordedValue(@Nullable String value) {
        if (INSUFFICIENT_EVIDENCE.name().equals(value)) return INSUFFICIENT_EVIDENCE;
        if (SUPERSEDED.name().equals(value)) return SUPERSEDED;
        return COALESCED.name().equals(value) ? COALESCED : REVIEWED;
    }

    /** The newer review a {@link #SUPERSEDED} run names, or null for any other run. */
    static @Nullable UUID coveringJobId(@Nullable JsonNode output) {
        if (fromJobOutput(output) != SUPERSEDED || output == null) return null;
        try {
            return UUID.fromString(output.path(COVERING_JOB_FIELD).asString());
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
    }
}
