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
    /** Ended with every practice it set out to check. */
    READY,
    /** Ended with some practices checked and some not. The review's practices say why. */
    PARTLY_CHECKED,
    /** Ended with no practice checked. The review's stop reason and its practices say why. */
    WITHHELD,
    FAILED,
    /** Cancelled. */
    STOPPED;

    /**
     * @param continuing whether a waiting review moved on, as {@link ReviewProgressDTO#continuing} says
     * @param unsettled items that no seal has decided yet: listed, or staged and still private
     * @param checked items that the review decided with an observation, a reused one or a trial's
     */
    public static ReviewStage of(ReviewStatus status, boolean continuing, long unsettled, long checked) {
        boolean sealed = unsettled == 0 && checked > 0;
        return switch (status) {
            case QUEUED -> sealed ? OBSERVATIONS_READY : continuing ? CONTINUING : QUEUED;
            case RUNNING -> sealed ? OBSERVATIONS_READY : CHECKING;
            case COMPLETE -> READY;
            case PARTIAL -> checked > 0 ? PARTLY_CHECKED : WITHHELD;
            case FAILED -> FAILED;
            case CANCELLED -> STOPPED;
        };
    }
}
