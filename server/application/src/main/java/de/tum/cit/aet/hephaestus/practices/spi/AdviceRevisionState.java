package de.tum.cit.aet.hephaestus.practices.spi;

/** Whether one advice revision may still back an answer (A2b's typed source). It mirrors the owner's view. */
public enum AdviceRevisionState {
    VISIBLE,
    /** A newer revision of the same entry exists. */
    SUPERSEDED,
    RETIRED,
    /** Feedback off, marked incorrect, or dismissed. */
    HIDDEN,
    /** Its text was removed with its evidence, or a source is gone. */
    STALE,
    MISSING
}
