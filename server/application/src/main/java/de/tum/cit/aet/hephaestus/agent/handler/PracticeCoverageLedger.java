package de.tum.cit.aet.hephaestus.agent.handler;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

/**
 * The coverage ledger a review run wrote about itself, as it sits in the job output.
 *
 * <p>One reader for the record {@code PiResultParser} validated on the way in: how many practices the
 * run was eligible for, how many it evaluated, and the per-practice outcomes. A run that wrote no
 * ledger, or one whose counts are not whole numbers, has shown nothing, and reads here as absent.
 */
public record PracticeCoverageLedger(
        @Nullable Integer eligible, @Nullable Integer evaluated, JsonNode outcomes) {

    private static final PracticeCoverageLedger NONE =
            new PracticeCoverageLedger(null, null, MissingNode.getInstance());

    public static PracticeCoverageLedger from(@Nullable JsonNode jobOutput) {
        if (jobOutput == null) {
            return NONE;
        }
        JsonNode coverage = jobOutput.path("practiceCoverage");
        if (!coverage.isObject()) {
            return NONE;
        }
        return new PracticeCoverageLedger(
                count(coverage.path("eligible")), count(coverage.path("evaluated")), coverage.path("outcomes"));
    }

    private static @Nullable Integer count(JsonNode node) {
        return node.isIntegralNumber() && node.asInt() >= 0 ? node.asInt() : null;
    }

    /**
     * Whether the run evaluated every practice it was eligible for. A run with no ledger has not shown
     * that it reached anything, so it does not get to claim it did: the parser drops a ledger it cannot
     * validate, and a run that never wrote one admits no observations either.
     */
    public boolean reachedEveryPractice() {
        return eligible != null && eligible.equals(evaluated);
    }
}
