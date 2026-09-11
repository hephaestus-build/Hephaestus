package de.tum.cit.aet.hephaestus.practices.model;

import org.jspecify.annotations.Nullable;

/**
 * What one observation says about the developer, read off {@link Presence} × {@link Assessment} (ADR 0022).
 *
 * <p>Derived, never persisted, so the axes and their reading cannot drift apart. The matrix is read here and
 * nowhere else.
 *
 * <p>The four applicable outcomes are categories, not ordered levels: a safe avoidance is not worth less than
 * a demonstrated strength. What the distinction selects is the mentoring response.
 */
public enum ObservationOutcome {
    /** The behaviour was there and it was right. Reinforce it against the concrete evidence. */
    DEMONSTRATED_STRENGTH,
    /** A harmful behaviour could have appeared and did not. Acknowledge without claiming mastery. */
    SAFE_AVOIDANCE,
    /** Something harmful was done. Explain the consequence and suggest a correction. */
    COMMISSION_PROBLEM,
    /** Something needed was left out. Scaffold the missing step. */
    OMISSION_GAP,
    NOT_APPLICABLE,
    UNDETERMINED;

    public static ObservationOutcome of(
            AssessmentStatus status, @Nullable Presence presence, @Nullable Assessment assessment) {
        if (status != AssessmentStatus.ASSESSED) {
            if (presence != null || assessment != null)
                throw new IllegalArgumentException("Unassessed axes must be null");
            return status == AssessmentStatus.NOT_APPLICABLE ? NOT_APPLICABLE : UNDETERMINED;
        }
        if (presence == null || assessment == null) throw new IllegalArgumentException("Assessed axes are required");
        return switch (presence) {
            case PRESENT -> assessment == Assessment.GOOD ? DEMONSTRATED_STRENGTH : COMMISSION_PROBLEM;
            case ABSENT -> assessment == Assessment.GOOD ? SAFE_AVOIDANCE : OMISSION_GAP;
        };
    }

    public static ObservationOutcome of(Observation observation) {
        return of(observation.getAssessmentStatus(), observation.getPresence(), observation.getAssessment());
    }

    /** Evidence in the developer's favour. */
    public boolean isPositive() {
        return this == DEMONSTRATED_STRENGTH || this == SAFE_AVOIDANCE;
    }

    /** Evidence against, and the reason a practice has something to work on. */
    public boolean isNegative() {
        return this == COMMISSION_PROBLEM || this == OMISSION_GAP;
    }

    /** Whether this outcome is a verdict at all, which is the grain a trend counts. */
    public boolean isApplicable() {
        return this != NOT_APPLICABLE && this != UNDETERMINED;
    }

    /**
     * Whether a defect-detector practice may claim this outcome as a strength.
     *
     * <p>Such a practice hunts an undesirable behaviour, so {@link #DEMONSTRATED_STRENGTH} is incoherent for
     * it, since what would be demonstrated is the defect. {@link #SAFE_AVOIDANCE} is the opposite case and is
     * exactly what a clean detector run proves: the behaviour could have appeared in the corpus the practice
     * bounds and did not.
     */
    public boolean isCoherentStrengthFor(boolean defectDetector) {
        return this == SAFE_AVOIDANCE || (this == DEMONSTRATED_STRENGTH && !defectDetector);
    }
}
