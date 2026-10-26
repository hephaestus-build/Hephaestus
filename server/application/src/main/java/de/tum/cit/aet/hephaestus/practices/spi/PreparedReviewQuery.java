package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.Optional;
import java.util.UUID;

/** Reads the review on the work that a review prepared, for the private feedback composed after it. */
public interface PreparedReviewQuery {
    /** The review on the work that the review prepared, as it was composed, or empty when it prepared none. */
    Optional<PreparedReviewOnWork> preparedReviewOnWork(long workspaceId, UUID reviewId);
}
