package de.tum.cit.aet.hephaestus.core.release;

public enum ReleaseCheckFailure {
    /** GitHub named a wait, which the next attempt honours. */
    RATE_LIMITED,
    /** GitHub could not be reached or answered with an error. */
    UNAVAILABLE,
    /** GitHub answered, but not with a release this checker understands. */
    MALFORMED
}
