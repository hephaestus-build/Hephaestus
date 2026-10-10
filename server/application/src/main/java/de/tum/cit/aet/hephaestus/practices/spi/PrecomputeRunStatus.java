package de.tum.cit.aet.hephaestus.practices.spi;

/** What became of one practice's precompute script in one review attempt. */
public enum PrecomputeRunStatus {
    /** The script ran to its end. */
    OK,
    /** The script did not run, because a model that it requires has no binding. It found no places. */
    SKIPPED,
    /** The script stopped with an error. */
    FAILED,
    /** The script was stopped at its deadline, or the stage deadline came before it started. */
    TIMED_OUT,
    /** The script was staged and reported nothing: the stage stopped or crashed first. */
    NOT_FINISHED
}
