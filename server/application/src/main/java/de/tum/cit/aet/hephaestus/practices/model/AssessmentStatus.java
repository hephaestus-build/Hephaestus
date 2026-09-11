package de.tum.cit.aet.hephaestus.practices.model;

import org.jspecify.annotations.Nullable;

/** Whether this practice could be assessed against this work, independently of its result. */
public enum AssessmentStatus {
    ASSESSED,
    /** A concrete fact establishes that this work offers no occasion for this practice. */
    NOT_APPLICABLE,
    /** Relevant evidence was captured and read, but does not settle the assessment. */
    UNDETERMINED;

    /** Reject contradictory axes rather than inventing or silently discarding a judgment. */
    public void validate(@Nullable Presence presence, @Nullable Assessment assessment, @Nullable Severity severity) {
        if (this == ASSESSED) {
            if (presence == null || assessment == null) {
                throw new IllegalArgumentException("ASSESSED requires presence and assessment");
            }
        } else if (presence != null || assessment != null) {
            throw new IllegalArgumentException("Unassessed observations require null presence and assessment");
        }
        if ((assessment == Assessment.BAD) != (severity != null)) {
            throw new IllegalArgumentException("Severity is required exactly for BAD assessments");
        }
    }
}
