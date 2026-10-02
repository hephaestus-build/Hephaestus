package de.tum.cit.aet.hephaestus.practices.model;

import org.jspecify.annotations.Nullable;

/** The evidence-bounded result of reviewing one practice against one piece of work. */
public enum Outcome {
    MET,
    NOT_MET,
    /** A concrete fact establishes that this work offers no occasion for this practice. */
    NOT_APPLICABLE,
    /** Relevant evidence was captured and read, but does not settle the assessment. */
    UNDETERMINED;

    public void validate(@Nullable Severity severity) {
        if ((this == NOT_MET) != (severity != null)) {
            throw new IllegalArgumentException("Severity is required exactly for NOT_MET outcomes");
        }
    }

    public boolean isMet() {
        return this == MET;
    }

    public boolean isNotMet() {
        return this == NOT_MET;
    }

    /** Only decided assessments contribute to the met-rate denominator. */
    public boolean isDecided() {
        return this == MET || this == NOT_MET;
    }
}
