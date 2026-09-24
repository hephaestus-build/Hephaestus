package de.tum.cit.aet.hephaestus.practices;

import java.io.Serial;

public class StalePracticeReleaseException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public StalePracticeReleaseException() {
        super("The practice or catalog offer changed. Reload the proposal before deciding.");
    }
}
