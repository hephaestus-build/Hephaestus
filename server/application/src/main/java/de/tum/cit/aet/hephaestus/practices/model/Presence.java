package de.tum.cit.aet.hephaestus.practices.model;

/**
 * Whether the practice's fixed target was present or absent, only when assessed. Neither value is
 * inherently positive or negative: combine it with target desirability ({@link Assessment}) to derive {@link Outcome}. A collection failure is a
 * review readiness decision, never an observation about the developer's work.
 */
public enum Presence {
    PRESENT,
    ABSENT
}
