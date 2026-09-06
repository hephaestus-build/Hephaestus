package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.ValidatedObservation;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * What a run's own coverage ledger permits it to say. A review may end having evaluated only some of
 * the practices it was eligible for; the runner records which ones it reached, and this is where that
 * record decides what the review is allowed to claim.
 */
final class ReviewCoverage {

    private ReviewCoverage() {}

    /**
     * Whether this review must keep quiet about having found nothing. A review that reached every
     * practice may say so; one that did not may report what it found and no more, because an all-clear
     * covers the practices nobody evaluated as much as the ones that were — a claim about work this
     * review never looked at. The observations are recorded either way, and a practice nobody reached
     * reads as unevaluated on the developer's practice page.
     */
    static boolean withholdsAllClear(@Nullable JsonNode jobOutput, List<ValidatedObservation> composable) {
        return !reachedEveryPractice(jobOutput) && composable.stream().noneMatch(DeliveryComposer::isProblem);
    }

    /**
     * The ledger the runner writes and {@code PiResultParser} validates before it reaches the job
     * output — the same record the practice trace reads to mark a practice unevaluated. A run with no
     * ledger has not shown that it reached anything, so it does not get to claim it did: the parser
     * drops a ledger it cannot validate, and a run that never wrote one admits no observations either.
     */
    private static boolean reachedEveryPractice(@Nullable JsonNode jobOutput) {
        if (jobOutput == null) return false;
        JsonNode coverage = jobOutput.path("practiceCoverage");
        if (!coverage.isObject()) return false;
        int eligible = coverage.path("eligible").asInt(-1);
        return eligible >= 0 && coverage.path("evaluated").asInt(-1) == eligible;
    }
}
