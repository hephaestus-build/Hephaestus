package de.tum.cit.aet.hephaestus.integration.outline.collection;

import java.io.Serial;

/**
 * Raised when a registration names a collection id that the live {@code collections.list} does not
 * contain — a typo, a deleted collection, or one the token cannot see. Mapped to
 * {@code 422 Unprocessable Entity} by {@link OutlineCollectionControllerAdvice}: the request is
 * well-formed, but the referenced collection does not exist in Outline.
 */
public class UnknownOutlineCollectionException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public UnknownOutlineCollectionException(String collectionId) {
        super("Collection \"" + collectionId + "\" was not found in Outline");
    }
}
