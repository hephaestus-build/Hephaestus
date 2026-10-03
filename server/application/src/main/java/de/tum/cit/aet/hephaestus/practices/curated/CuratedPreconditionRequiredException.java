package de.tum.cit.aet.hephaestus.practices.curated;

import java.io.Serial;

public class CuratedPreconditionRequiredException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CuratedPreconditionRequiredException() {
        super("The If-Match header must contain the current ETag of the catalog entry.");
    }
}
