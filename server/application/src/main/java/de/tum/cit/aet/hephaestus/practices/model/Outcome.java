package de.tum.cit.aet.hephaestus.practices.model;

import org.jspecify.annotations.Nullable;

/** The consequence of presence and the specified behavior's contextual desirability; never independently assigned. */
public enum Outcome {
    POSITIVE,
    NEGATIVE;

    public static @Nullable Outcome of(@Nullable Presence presence, @Nullable Assessment assessment) {
        if (presence == null && assessment == null) return null;
        if (presence == null || assessment == null)
            throw new IllegalArgumentException("Presence and assessment must both be supplied or both be null");
        return (presence == Presence.PRESENT) == (assessment == Assessment.GOOD) ? POSITIVE : NEGATIVE;
    }
}
