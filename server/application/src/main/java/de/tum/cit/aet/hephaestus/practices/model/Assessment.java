package de.tum.cit.aet.hephaestus.practices.model;

/** Whether the specified behavior in context is desirable (GOOD) or undesirable (BAD), not the observation's outcome. */
public enum Assessment {
    /** The specified behavior is desirable in context: its presence is positive and its absence negative. */
    GOOD,
    /** The specified behavior is undesirable in context: its presence is negative and its absence positive. */
    BAD,
}
