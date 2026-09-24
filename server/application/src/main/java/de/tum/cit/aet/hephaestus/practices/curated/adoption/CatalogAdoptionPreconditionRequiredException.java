package de.tum.cit.aet.hephaestus.practices.curated.adoption;

import java.io.Serial;

public class CatalogAdoptionPreconditionRequiredException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CatalogAdoptionPreconditionRequiredException() {
        super("If-Match must contain the adoption preview ETag.");
    }
}
