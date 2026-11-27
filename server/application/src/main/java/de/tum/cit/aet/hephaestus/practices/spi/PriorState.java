package de.tum.cit.aet.hephaestus.practices.spi;

/** What the mentor may know about an entry before it decides again. Only {@link #VISIBLE} comes with citations. */
public enum PriorState {
    VISIBLE,
    /** Stale, incomplete, feedback off, or marked incorrect: the owner sees nothing. */
    HIDDEN,
    RETIRED,
    DISMISSED
}
