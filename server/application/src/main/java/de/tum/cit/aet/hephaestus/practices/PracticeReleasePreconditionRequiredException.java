package de.tum.cit.aet.hephaestus.practices;

import java.io.Serial;

public class PracticeReleasePreconditionRequiredException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public PracticeReleasePreconditionRequiredException() {
        super("If-Match must name the practice release proposal being reviewed");
    }
}
