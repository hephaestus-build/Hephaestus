package de.tum.cit.aet.hephaestus.practices.model;

/**
 * Whether an observation is good or bad for the developer (ADR 0022). This is the valence axis,
 * orthogonal to {@link Presence}: the detector resolves it <em>per observation</em> by reading the
 * practice criteria and {@code what_good_looks_like}, so a single practice can emit both
 * {@code GOOD} and {@code BAD} observations.
 *
 * <p>NULL exactly when {@link AssessmentStatus} is not ASSESSED. "Is this a
 * problem?" is {@code assessment == BAD}; "is this a strength?" is {@code assessment == GOOD}.
 */
public enum Assessment {
    /** The observation reflects well on the developer — a strength to acknowledge. */
    GOOD,
    /** The observation is a problem the developer should act on. {@link Severity} carries the impact. */
    BAD,
}
