package de.tum.cit.aet.hephaestus.practices.curated;

import java.io.Serial;

public class CuratedCatalogConflictException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CuratedCatalogConflictException(String message) {
        super(message);
    }

    public CuratedCatalogConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
