package de.tum.cit.aet.hephaestus.practices.model;

/** Impact of a negative outcome. Positive and unassessed observations carry no severity. */
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
