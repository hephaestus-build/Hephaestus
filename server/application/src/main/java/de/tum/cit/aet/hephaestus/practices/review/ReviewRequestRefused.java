package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;

/**
 * A member's item review request that {@link ReviewRequestPolicy} refuses. The request answers with the reason, not
 * with an error, and no review rows were written.
 */
final class ReviewRequestRefused extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final ReviewRequestReason reason;

    ReviewRequestRefused(ReviewRequestReason reason) {
        super(reason.name(), null, false, false);
        this.reason = reason;
    }

    ReviewRequestReason reason() {
        return reason;
    }
}
