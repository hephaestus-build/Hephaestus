package de.tum.cit.aet.hephaestus.core.release;

/** Why the last check did not complete, reduced to what an administrator can act on. */
public enum ReleaseCheckFailure {
    /** GitHub refused the request and named a wait; the next attempt honours it. */
    RATE_LIMITED,
    /** GitHub could not be reached or answered with an error. */
    UNAVAILABLE,
    /** GitHub answered, but not with a release this checker understands. */
    MALFORMED
}
