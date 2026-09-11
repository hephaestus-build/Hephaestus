package de.tum.cit.aet.hephaestus.practices.model;

/**
 * Severity level for a practice observation — the coaching band of a {@code BAD} observation.
 *
 * <p>{@link Presence} captures whether the signal was PRESENT/ABSENT and {@link Assessment} its GOOD/BAD
 * valence; severity captures <em>how important</em> a BAD observation is. It is set only when
 * {@code assessment = BAD} (null on GOOD or unassessed observations): an {@code ABSENT, BAD}
 * gap can be MINOR (style nit) or CRITICAL (security vulnerability).
 */
public enum Severity {
    /** Must be acted on now — e.g. a leaked secret or a security vulnerability. */
    CRITICAL,
    /** A real problem that should be fixed before the work is considered done. */
    MAJOR,
    /** A minor issue or style nit; worth raising but not blocking. */
    MINOR,
    /** Informational only — a low-stakes note, no action expected. */
    INFO,
}
