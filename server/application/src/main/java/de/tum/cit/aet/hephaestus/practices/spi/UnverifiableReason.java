package de.tum.cit.aet.hephaestus.practices.spi;

/** Why a result's {@link Currentness#UNVERIFIABLE} currentness cannot be checked against the work. */
public enum UnverifiableReason {
    /**
     * The record of what the review read is missing, or it comes from an earlier version of the review. No later read
     * can verify the result.
     */
    RECORD_MISSING,
    /**
     * The record exists, but this server cannot read the current work now: for example, its repository mirror lacks
     * the reviewed commits, or the source is not readable. A later read can verify the result.
     */
    WORK_UNREADABLE;
}
