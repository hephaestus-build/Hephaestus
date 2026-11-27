package de.tum.cit.aet.hephaestus.practices.review.dto;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** The one state a status display shows for an item review, from its status and how its practices stand. */
@Schema(
        description = "The one state a status display shows for an item review. It follows from stored states only: it"
                + " estimates no share of the work and no remaining time, and it carries no observation.")
public enum ReviewStage {
    /** Waits to start. */
    QUEUED,
    /** An attempt ended some practices, and the review waits to continue with the rest. */
    CONTINUING,
    /** An attempt works on the practices, or the review has not sealed what they ended. */
    CHECKING,
    /** The observations are sealed, and the review has not ended yet, for example while it composes feedback. */
    OBSERVATIONS_READY,
    /**
     * Ended with every practice it set out to check. The review status can still be Partial, for example when its
     * feedback was not confirmed, and the stop reason says why.
     */
    READY,
    /** Ended with some practices checked and some not. The review's stop reason and its practices say why. */
    PARTLY_CHECKED,
    /** Ended with no practice checked. The review's stop reason and its practices say why. */
    WITHHELD,
    FAILED,
    /** Cancelled. */
    STOPPED;

    /**
     * @param ended practices that an attempt ended, as {@link ReviewProgressDTO#practicesEnded} counts them
     * @param continuing whether a waiting review moved on, as {@link ReviewProgressDTO#continuing} says
     * @param unsettled items that no seal has decided yet: listed, or staged and still private
     * @param checked items that the review decided with an observation, a reused one or a trial's
     * @param withheld items that the review ended without checking
     */
    public static ReviewStage of(
            ReviewStatus status, long ended, boolean continuing, long unsettled, long checked, long withheld) {
        boolean sealed = unsettled == 0 && checked > 0;
        return switch (status) {
            // Waiting with every practice ended and none sealed: the next attempt only has to seal them.
            case QUEUED -> sealed ? OBSERVATIONS_READY : continuing ? CONTINUING : ended > 0 ? CHECKING : QUEUED;
            case RUNNING -> sealed ? OBSERVATIONS_READY : CHECKING;
            case COMPLETE -> READY;
            // A review also ends as Partial for a reason that left no practice unchecked.
            case PARTIAL -> checked == 0 ? WITHHELD : unsettled + withheld > 0 ? PARTLY_CHECKED : READY;
            case FAILED -> FAILED;
            case CANCELLED -> STOPPED;
        };
    }
}
