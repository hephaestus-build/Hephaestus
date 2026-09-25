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
        return !PracticeCoverageLedger.from(jobOutput).reachedEveryPractice()
                && composable.stream().noneMatch(DeliveryComposer::isProblem);
    }
}
