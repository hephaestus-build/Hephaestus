package de.tum.cit.aet.hephaestus.practices.review;

import java.io.Serial;

public class PracticeReviewPreconditionRequiredException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    PracticeReviewPreconditionRequiredException() {
        super("The If-Match header must contain the current ETag of the practice review settings.");
    }
}
