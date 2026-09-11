package de.tum.cit.aet.hephaestus.practices.model;

/**
 * Whether the practice's fixed target was present or absent, only when assessed. Neither value is
 * inherently good or bad: that judgment belongs to {@link Assessment}. A collection failure is a
 * review readiness decision, never an observation about the developer's work.
 */
public enum Presence {
    PRESENT,
    ABSENT
}
