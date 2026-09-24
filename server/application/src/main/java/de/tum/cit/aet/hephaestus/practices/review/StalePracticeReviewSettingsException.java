package de.tum.cit.aet.hephaestus.practices.review;

import java.io.Serial;

public class StalePracticeReviewSettingsException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    StalePracticeReviewSettingsException() {
        super("Practice-review settings changed after they were loaded");
    }
}
