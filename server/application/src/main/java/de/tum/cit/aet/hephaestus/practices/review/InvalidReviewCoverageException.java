package de.tum.cit.aet.hephaestus.practices.review;

import java.io.Serial;

public class InvalidReviewCoverageException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    InvalidReviewCoverageException(String message) {
        super(message);
    }
}
