package de.tum.cit.aet.hephaestus.practices.observation;

import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO;

/**
 * The one scale both a practice standing and the group standing above it are read off.
 *
 * <p>Both answer "what share of this was positive?" and differ only in what they average: a practice averages
 * its recent opportunities, a group averages its practices. One classification keeps the two levels from
 * applying different bars.
 *
 * <p>The thresholds are asymmetric on purpose. {@code STRENGTH} is a claim about the developer and needs a
 * majority well past the middle; {@code DEVELOPING} only points at where attention would pay off.
 */
final class StandingScale {

    /**
     * Positive share ABOVE which the subject reads as a strength. Short of unanimity, so a dozen practices are
     * not held back forever by whichever single one is currently amber.
     */
    static final double STRENGTH_SHARE = 0.8;

    /**
     * Positive share AT or above which the subject still reads as mixed rather than developing.
     *
     * <p>Placed between ONE setback and a PATTERN of them. Under the standing's recency weights a single
     * problem on the newest piece of reviewed work scores {@code 0.384} and two in a row score {@code 0.138}; a boundary at
     * one half put both on the same side, so one slip after a clean run read as "needs attention". Both scores
     * hold for one to four decided pieces of work, since a practice's share is measured against the weight of a
     * full window ({@code PracticeTrend#recentMetShare}), so the boundary needs no second value for a short history.
     */
    static final double MIXED_SHARE = 0.37;

    private StandingScale() {}

    /**
     * Classifies a positive share in {@code [0,1]}. The caller must know there is evidence at all: this scale
     * cannot tell "half of it went badly" from "we know nothing", so 0.0 must never stand in for the latter.
     */
    static PracticeStandingDTO.Standing classify(double positiveShare) {
        if (positiveShare > STRENGTH_SHARE) {
            return PracticeStandingDTO.Standing.STRENGTH;
        }
        if (positiveShare >= MIXED_SHARE) {
            return PracticeStandingDTO.Standing.MIXED;
        }
        return PracticeStandingDTO.Standing.DEVELOPING;
    }
}
