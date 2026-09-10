package de.tum.cit.aet.hephaestus.practices.review;

import java.io.Serial;

public class PracticeReviewPreconditionRequiredException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    PracticeReviewPreconditionRequiredException() {
        super("If-Match must contain the current practice-review settings ETag");
    }
}
