package de.tum.cit.aet.hephaestus.practices.spi;

/**
 * What a standing was read from, from the strongest basis to the weakest. A standing from current reviews rests on
 * results with current proof. A standing from earlier reviews rests only on outcomes recorded before the current
 * result model: a recorded historical assessment, never current proof, read only where no current review reached the
 * practice. When a recorded outcome names no practice version, the criteria it was assessed against are unknown.
 */
public enum StandingBasis {
    CURRENT_REVIEWS,
    EARLIER_REVIEWS,
    EARLIER_REVIEWS_CRITERIA_UNKNOWN;

    /** Whether the standing rests on earlier reviews and carries their label. */
    public boolean isEarlierReviews() {
        return this != CURRENT_REVIEWS;
    }
}
