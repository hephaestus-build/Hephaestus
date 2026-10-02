package de.tum.cit.aet.hephaestus.practices.review;

/**
 * How the practice review was triggered — determines which workspace-level
 * toggle is checked by {@link ReviewGate}.
 */
public enum TriggerMode {
    /** Event-driven (PR created, synchronized, review submitted). */
    AUTO,
    /** Explicit user action (bot command {@code /hephaestus review}). */
    MANUAL,
}
