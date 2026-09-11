package de.tum.cit.aet.hephaestus.practices.model;

/** Whether the fixed target behaviour is desirable (GOOD) or undesirable (BAD), not the observation's outcome. */
public enum Assessment {
    /** The target behaviour is desirable: its presence is positive and its absence negative. */
    GOOD,
    /** The target behaviour is undesirable: its presence is negative and its absence positive. */
    BAD,
}
