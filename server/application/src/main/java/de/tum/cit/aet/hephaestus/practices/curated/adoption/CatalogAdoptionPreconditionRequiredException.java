package de.tum.cit.aet.hephaestus.practices.curated.adoption;

import java.io.Serial;

public class CatalogAdoptionPreconditionRequiredException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CatalogAdoptionPreconditionRequiredException() {
        super("The If-Match header must contain the ETag of the adoption preview.");
    }
}
